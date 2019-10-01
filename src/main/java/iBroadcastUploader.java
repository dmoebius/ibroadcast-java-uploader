import org.json.JSONException;
import org.json.JSONObject;

import javax.activation.MimetypesFileTypeMap;
import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.Collections.emptySet;

/**
 * For testing and development, create a free account at iBroadcast.com. The
 * script is run in a parent directory (only search current directory and child
 * directories) and find all supported music files
 * <p>
 * The script works by:
 * <p>
 * java -jar dist/ibroadcast-uploader.jar <email_address> <password>
 * <p>
 * The basic steps are:
 * <p>
 * 1. Log in with a username/password into iBroadcast 2. Capture the supported
 * file types from the server 3. Use user_id and token for subsequent requests
 * returned from initial request 4. Get md5 listing of user's files (songs in
 * their library) from iBroadcast server 5. Find supported music files locally,
 * give the user option to list those files found 6. Upload files to iBroadcast
 * via http/post 7. Skip songs already uploaded (via md5 compare)
 */
public class iBroadcastUploader {

    private static final String CHARSET = "UTF-8";
    private static final CharSequence CR_LF = "\r\n";
    private static final char[] DIGITS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

    private static final ThreadLocal<MessageDigest> md5Digest = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    });

    /**
     * @param args command line arguments
     */
    public static void main(String[] args) throws IOException {
        // command line arguments are email address and password
        if (args.length < 2 || args[0].length() == 0 || args[1].length() == 0) {
            var usage = "Run this script in the parent directory of your music files.\n"
                    + "usage: java -jar ibroadcast-uploader.jar"
                    + " <email_address> <password>\n";
            error(usage);
        }

        // Inital request, verifies username/password, returns user_id/token and
        // supported file types
        Map<String, Object> req = new HashMap<>();
        req.put("mode", "status");
        req.put("email_address", args[0]);
        req.put("password", args[1]);
        req.put("version", ".1");
        req.put("client", "java uploader script");
        req.put("supported_types", 1);

        // Convert request to json
        var jsonOut = new JSONObject(req).toString();

        // Post it to json.ibroadcast.com
        message("Login...\n");
        var j = post("https://json.ibroadcast.com/s/JSON/" + req.get("mode"), jsonOut);

        String userId, token;
        try {
            // Get user id and token from response
            var user = j.getJSONObject("user");
            userId = user.getString("id");
            token = user.getString("token");

        } catch (JSONException e) {
            error("Login failed. Please check your email address, password combination\n");
            // java compiler needs it
            return;
        }

        // convert the supported array to a map
        var supportedArray = j.getJSONArray("supported");

        Map<String, Boolean> supported = new HashMap<>();

        for (var i = 0; i < supportedArray.length(); i++) {
            var supportedExtension = supportedArray.getJSONObject(i);
            supported.put(supportedExtension.getString("extension"), true);
        }

        // current working directory
        var userDir = System.getProperty("user.dir");
        // Get files from current working directory
        message("Collecting files to upload...\n");
        var listFileTree = listFileTree(new File(userDir), supported.keySet());
        // confirm upload with user
        var upload = confirm(listFileTree);
        // upload
        if (upload) {
            uploadFiles(listFileTree, userId, token);
        }
    }

    /**
     * Prompt for upload.
     *
     * @param files music files found
     */
    private static boolean confirm(Collection<File> files) {
        message("Found " + files.size()
                + " files. Press 'L' to list, or 'U' to start the upload.\n");

        var list = Pattern.compile("L", Pattern.CASE_INSENSITIVE);
        var upload = Pattern.compile("U", Pattern.CASE_INSENSITIVE);

        var sc = new Scanner(System.in);

        try (sc) {
            var userInput = sc.next();
            if (list.matcher(userInput).matches()) {
                message("\nListing found, supported files\n");
                for (var file : files) {
                    message(" - " + file + "\n");
                }
                message("Press 'U' to start the upload if this looks reasonable.\n");
                userInput = sc.next();
                if (upload.matcher(userInput).matches()) {
                    message("Starting upload\n");
                    return true;
                } else {
                    message("aborted.\n");
                    return false;
                }
            } else if (upload.matcher(userInput).matches()) {
                return true;
            } else {
                message("aborted.\n");
                return false;
            }
        }
    }

    /**
     * Get MD5 sums of all known files stored on server.
     *
     * @param userId user id
     * @param token  user token
     * @return set of MD5 strings
     */
    private static Set<String> getMD5(String userId, String token) throws IOException {
        var md5Object = post("https://sync.ibroadcast.com",
                "user_id=" + userId + "&token=" + token,
                "application/x-www-form-urlencoded");
        var jsonArray = md5Object.getJSONArray("md5");
        var ret = new HashSet<String>();
        for (var i = 0; i < jsonArray.length(); i++) {
            if (!jsonArray.isNull(i)) {
                var md5 = jsonArray.getString(i);
                ret.add(md5);
            }
        }
        return ret;
    }

    /**
     * Get supported media files from directory
     *
     * @param dir        top-level directory containing media files
     * @param extensions supported media file extensions
     * @return collection of files
     */
    private static Collection<File> listFileTree(File dir, Set<String> extensions) {
        Set<File> fileTree = new HashSet<>();
        var files = dir.listFiles();
        if (files == null) {
            error("not a directory: " + dir.getAbsolutePath());
            return emptySet(); // compiler needs this
        }
        for (var file : files) {
            // skip hidden
            if (Pattern.compile("^\\..*").matcher(file.getName()).matches()) {
                continue;
            }
            if (file.isFile()) {
                // file extension
                var extensionMatcher = Pattern.compile(".*(\\..{2,5})")
                        .matcher(file.getName());
                if (extensionMatcher.matches()) {
                    // file extension
                    var extension = extensionMatcher.group(1);
                    if (extensions.contains(extension)) {
                        fileTree.add(file);
                    }
                }
            } else {
                // dir, descend
                fileTree.addAll(listFileTree(file, extensions));
            }
        }
        return fileTree;
    }

    /**
     * Upload files.
     */
    private static void uploadFiles(Collection<File> listFileTree,
                                    String userId, String token)
            throws IOException {
        message("Getting checksums...\n");
        var md5 = getMD5(userId, token);
        message("Starting upload...\n");
        listFileTree.parallelStream().forEach(file -> {
            try {
                uploadFile(file, userId, token, md5);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        message("Complete.\n");
    }

    private static void uploadFile(File file, String userId, String token, Set<String> md5)
            throws IOException {
        String hashText = md5sum(file);
        if (md5.contains(hashText)) {
            message("Skipping:  " + file + ", already uploaded\n");
        } else {
            message("Uploading: " + file + "\n");
            var status = uploadFile(file, userId, token);
            if (status == HttpURLConnection.HTTP_OK) {
                message("Uploading: " + file + ": Done!\n");
            } else {
                message("Uploading: " + file + ": Failed.\n");
            }
        }
    }

    /**
     * Talk with iBroadcast, posting data
     *
     * @param url     post url
     * @param content post data
     * @return response content, in json object format
     */
    private static JSONObject post(String url, String content) throws IOException {
        return post(url, content, null);
    }

    /**
     * Talk with iBroadcast, posting data
     *
     * @param url         post url
     * @param content     post data
     * @param contentType content type or null
     * @return response content, in json object format
     */
    private static JSONObject post(String url, String content, String contentType)
            throws IOException {
        var con = (HttpsURLConnection) new URL(url).openConnection();

        // add request method
        con.setRequestMethod("POST");

        if (contentType != null) {
            con.setRequestProperty("Content-Type", contentType);
        }

        // Send post request
        con.setUseCaches(false);
        con.setDoOutput(true); // indicates POST method
        con.setDoInput(true);

        var wr = new DataOutputStream(con.getOutputStream());
        wr.writeBytes(content);
        wr.flush();
        wr.close();

        var in = new BufferedReader(new InputStreamReader(con.getInputStream()));
        String inputLine;
        var response = new StringBuilder();

        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();

        con.disconnect();

        // Parse the response
        return new JSONObject(response.toString());
    }

    /**
     * Talk with iBroadcast, posting data and uploading file
     *
     * @param file   file to upload
     * @param userId user id
     * @param token  user token
     * @return response code
     */
    private static int uploadFile(File file, String userId, String token)
            throws IOException {
        var con = (HttpsURLConnection) new URL("https://sync.ibroadcast.com")
                .openConnection();

        // add request method
        con.setRequestMethod("POST");

        // Send post request
        con.setUseCaches(false);
        con.setDoOutput(true); // indicates POST method
        con.setDoInput(true);

        // creates a unique boundary based on time stamp
        var boundary = "===" + System.currentTimeMillis() + "===";
        var contentType = "multipart/form-data; boundary=" + boundary;

        con.setRequestProperty("Content-Type", contentType);
        con.setRequestProperty("User-Agent", "java uploader");
        var outputStream = con.getOutputStream();
        var writer = new PrintWriter(new OutputStreamWriter(
                outputStream, CHARSET), true);

        addFilePart(writer, outputStream, file, boundary);
        addParameter(writer, "file_path", file.getPath(), boundary);
        addParameter(writer, "method", "java uploader", boundary);
        addParameter(writer, "user_id", userId, boundary);
        addParameter(writer, "token", token, boundary);

        writer.append("--").append(boundary).append("--").append(CR_LF);
        writer.flush();
        writer.close();

        con.disconnect();

        return con.getResponseCode();
    }

    /**
     * Adds a upload file section to the request
     */
    private static void addFilePart(PrintWriter writer, OutputStream outputStream,
                                    File uploadFile, String boundary)
            throws IOException {
        var fileName = uploadFile.getName();
        writer.append("--").append(boundary).append(CR_LF);
        var contentType = MimetypesFileTypeMap.getDefaultFileTypeMap().getContentType(uploadFile);
        writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(fileName).append("\"").append(CR_LF)
                .append("Content-Type: ").append(contentType).append(CR_LF)
                .append(CR_LF);
        writer.flush();

        var inputStream = new FileInputStream(uploadFile);
        var buffer = new byte[204800];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
        outputStream.flush();
        inputStream.close();

        writer.append(CR_LF);
        writer.flush();
    }

    /**
     * Adds a parameter to the request
     */
    private static void addParameter(PrintWriter writer, String name,
                                     String value, String boundary) {
        writer.append("--").append(boundary).append(CR_LF)
                .append("Content-Disposition: form-data; name=\"")
                .append(name).append("\"").append(CR_LF)
                .append(CR_LF).append(value).append(CR_LF);
        writer.flush();
    }

    /**
     * error message and exit
     */
    private static void error(String error) {
        message(error);
        System.exit(1);
    }

    /**
     * print message
     */
    private static void message(String message) {
        System.out.print(message);
        System.out.flush();
    }

    private static String md5sum(File file) throws IOException {
        var buffer = new byte[204800];
        var digest = md5Digest.get();
        try (var dis = new DigestInputStream(new FileInputStream(file), digest)) {
            while (dis.read(buffer) != -1) { /*intentionally left blank*/ }
        }
        return encodeHex(digest.digest());
    }

    private static String encodeHex(final byte[] data) {
        final int l = data.length;
        final char[] out = new char[l << 1];
        for (int i = 0, j = 0; i < l; i++) {
            out[j++] = DIGITS[(0xF0 & data[i]) >>> 4];
            out[j++] = DIGITS[0x0F & data[i]];
        }
        return new String(out);
    }
}
