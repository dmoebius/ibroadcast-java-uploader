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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static java.util.Collections.emptySet;

/**
 * For testing and development, create a free account at iBroadcast.com. The
 * script is run in a parent directory (only search current directory and child
 * directories) and find all supported music files.
 * <p>
 * The script works by:
 * <pre>
 * java -jar ibroadcast-uploader.jar &lt;email-address&gt; &lt;password&gt; [&lt;dir&gt;]
 * </pre>
 * The basic steps are:
 * <ol>
 * <li>Log in with a username/password into iBroadcast</li>
 * <li>Capture the supported file types from the server</li>
 * <li>Use user_id and token for subsequent requests returned from initial request</li>
 * <li>Get md5 listing of user's files (songs in their library) from iBroadcast server</li>
 * <li>Find supported music files locally, give the user option to list those files found</li>
 * <li>Upload files to iBroadcast via http/post</li>
 * <li>Skip songs already uploaded (via md5 compare)</li>
 * </ol>
 */
public class iBroadcastUploader {

    private static final String CHARSET = "UTF-8";
    private static final CharSequence CR_LF = "\r\n";
    private static final int bufferSize = 32 * 4096;

    /**
     * @param args command line arguments
     */
    public static void main(String[] args) throws IOException {
        // command line arguments are email address and password
        if (args.length < 2 || args.length > 3
                || args[0].length() == 0 || args[1].length() == 0
                || args[0].equals("-h") || args[0].equals("--help") || args[0].equals("/?")) {
            var usage = "Run this script in the parent directory of your music files\n"
                    + "or give the music directory as third argument.\n"
                    + "\n"
                    + "Usage: java -jar ibroadcast-uploader.jar"
                    + " <email-address> <password> [<dir>]\n";
            error(usage);
        }

        // Inital request, verifies username/password, returns user_id/token and
        // supported file types
        var userData = login(args[0], args[1]);

        String userId, token;
        try {
            // Get user id and token from response
            var user = userData.getJSONObject("user");
            userId = user.getString("id");
            token = user.getString("token");

        } catch (JSONException e) {
            error("Login failed. Please check your email address, password combination\n");
            // java compiler needs it
            return;
        }

        // convert the supported extensions array to a set
        var supported = getSupportedExtensions(userData);

        // current working directory
        var dir = args.length == 3 ? args[2] : System.getProperty("user.dir");
        // Get files from current working directory
        var rootDir = new File(dir);
        message("Collecting files to upload in " + rootDir.getAbsolutePath() + " ...\n");
        var listFileTree = listFileTree(rootDir, supported);
        message("Found " + listFileTree.size() + " files.\n");
        if (listFileTree.isEmpty()) {
            return;
        }
        // confirm upload with user
        var upload = confirm(listFileTree);
        // upload
        if (upload) {
            uploadFiles(listFileTree, userId, token, rootDir);
        }
    }

    private static JSONObject login(String email, String password) throws IOException {
        message("Login...\n");

        Map<String, Object> req = new HashMap<>();
        req.put("mode", "status");
        req.put("email_address", email);
        req.put("password", password);
        req.put("version", ".1");
        req.put("client", "java uploader script");
        req.put("supported_types", 1);

        // Convert request to json
        var jsonOut = new JSONObject(req).toString();

        // Post it to json.ibroadcast.com
        return post("https://json.ibroadcast.com/s/JSON/status", jsonOut, "application/json");
    }

    private static Set<String> getSupportedExtensions(JSONObject userData) {
        var supportedArray = userData.getJSONArray("supported");
        var supported = new HashSet<String>();
        for (var i = 0; i < supportedArray.length(); i++) {
            var supportedExtension = supportedArray.getJSONObject(i);
            supported.add(supportedExtension.getString("extension"));
        }
        // remove playlist extensions; iBroadcase constantly reuploads them even if MD5 is unchanged
        supported.remove(".m3u");
        supported.remove(".m3u8");
        return supported;
    }

    /**
     * Prompt for upload.
     *
     * @param files music files found
     */
    private static boolean confirm(Collection<File> files) {
        message("Press 'L' to list, 'U' to start the upload, or 'Q' to quit.\n");

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
                message("Press 'U' to start the upload if this looks reasonable, " +
                        "or 'Q' to quit.\n");
                userInput = sc.next();
            }
            if (upload.matcher(userInput).matches()) {
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
    private static Collection<File> listFileTree(File dir, Set<String> extensions) throws IOException {
        if (!dir.exists() || !dir.isDirectory()) {
            error("Error: not a directory: " + dir.getAbsolutePath());
            return emptySet(); // compiler needs this
        }

        var fileTree = new TreeSet<File>();
        var hiddenFile = Pattern.compile("^\\..*");
        var extensionMatcher = Pattern.compile(".*(\\..{2,5})");

        Files.walkFileTree(dir.toPath(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                var filename = path.getFileName().toString();
                if (attrs.isRegularFile() && !hiddenFile.matcher(filename).matches()) {
                    var extensionMatch = extensionMatcher.matcher(filename);
                    if (extensionMatch.matches()) {
                        // file extension
                        var extension = extensionMatch.group(1);
                        if (extensions.contains(extension)) {
                            fileTree.add(path.toFile());
                        }
                    }
                }
                return super.visitFile(path, attrs);
            }
        });

        return fileTree;
    }

    /**
     * Upload files.
     */
    private static void uploadFiles(Collection<File> listFileTree,
                                    String userId, String token, File rootDir)
            throws IOException {
        message("Getting checksums...\n");
        var total = listFileTree.size();
        var count = new AtomicInteger();
        var knownMD5 = getMD5(userId, token);
        var md5CacheFile = new File(rootDir, "ib-md5-cache.json");
        var md5Cache = MD5Cache.load(md5CacheFile);

        try {
            message("Starting upload...\n");
            listFileTree.parallelStream().forEach(file -> {
                var relativePath = computeRelativePath(file, rootDir);
                var cnt = count.incrementAndGet();
                try {
                    uploadFile(file, relativePath, userId, token, knownMD5, md5Cache, cnt, total);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            message("Processed " + listFileTree.size() + " files.\n");
        } finally {
            md5Cache.save(md5CacheFile);
        }
    }

    private static void uploadFile(File file, String path,
                                   String userId, String token,
                                   Set<String> knownMD5, MD5Cache md5Cache,
                                   int count, int total)
            throws IOException {
        var hashText = md5Cache.getMD5Sum(file, path);
        int len = (int) (Math.log10(total) + 1);
        var prefix = String.format("%" + len + "d/%" + len + "d: ", count, total);
        if (knownMD5.contains(hashText)) {
            message(prefix + "Skipping:  " + path + "\n");
        } else {
            message(prefix + "Uploading: " + path + "\n");
            var status = uploadFile(file, path, userId, token);
            if (status == HttpURLConnection.HTTP_OK) {
                message(prefix + "Uploaded:  " + path + "\n");
            } else {
                message(prefix + "Failed:    " + path + "\n");
            }
        }
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
     * @param file         file to upload
     * @param relativePath path relative to root dir
     * @param userId       user id
     * @param token        user token
     * @return response code
     */
    private static int uploadFile(File file, String relativePath, String userId, String token)
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

        try (var outputStream = con.getOutputStream()) {
            var writer = new PrintWriter(new OutputStreamWriter(
                    outputStream, CHARSET), true);

            addFilePart(writer, outputStream, file, boundary);
            addParameter(writer, "file_path", relativePath, boundary);
            addParameter(writer, "method", "java uploader", boundary);
            addParameter(writer, "user_id", userId, boundary);
            addParameter(writer, "token", token, boundary);

            writer.append("--").append(boundary).append("--").append(CR_LF);
            writer.flush();
            writer.close();
        }

        con.disconnect();

        return con.getResponseCode();
    }

    private static String computeRelativePath(File file, File rootDir) {
        return rootDir.toPath().relativize(file.toPath()).toString();
    }

    /**
     * Adds a upload file section to the request
     */
    private static void addFilePart(PrintWriter writer, OutputStream outputStream,
                                    File uploadFile, String boundary)
            throws IOException {
        var fileName = uploadFile.getName();
        var contentType = MimetypesFileTypeMap.getDefaultFileTypeMap().getContentType(uploadFile);
        writer.append("--").append(boundary).append(CR_LF)
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(fileName).append("\"").append(CR_LF)
                .append("Content-Type: ").append(contentType).append(CR_LF)
                .append(CR_LF);
        writer.flush();

        var inputStream = new FileInputStream(uploadFile);
        var buffer = new byte[bufferSize];
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

}
