import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.activation.MimetypesFileTypeMap;
import javax.net.ssl.HttpsURLConnection;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * For testing and development, create a free account at iBroadcast.com. The
 * script is run in a parent directory (only search current directory and child
 * directories) and find all supported music files
 * 
 * The script works by:
 * 
 * java -jar dist/ibroadcast-uploader.jar <email_address> <password>
 * 
 * The basic steps are:
 * 
 * 1. Log in with a username/password into iBroadcast 2. Capture the supported
 * file types from the server 3. Use user_id and token for subsequent requests
 * returned from initial request 4. Get md5 listing of user's files (songs in
 * their library) from iBroadcast server 5. Find supported music files locally,
 * give the user option to list those files found 6. Upload files to iBroadcast
 * via http/post 7. Skip songs already uploaded (via md5 compare)
 * 
 */
public class iBroadcastUploader {

	private static final String CHARSET = "UTF-8";
	private static final CharSequence CR_LF = "\r\n";

	/**
	 * @param args
	 *            command line arguments
	 * @throws IOException
	 * @throws MalformedURLException
	 * @throws NoSuchAlgorithmException
	 */
	public static void main(String[] args) throws MalformedURLException,
			IOException, NoSuchAlgorithmException {
		// command line arguments are email address and password
		if (args.length < 2 || args[0].length() == 0 || args[1].length() == 0) {
			String usage = "Run this script in the parent directory of your music files.\n"
					+ "usage: java -jar dist/ibroadcast-uploader.jar"
					+ " <email_address> <password>\n";
			error(usage);
		}

		// Inital request, verifies username/password, returns user_id/token and
		// supported file types
		Map<String, Object> req = new HashMap<String, Object>();
		req.put("mode", "status");
		req.put("email_address", args[0]);
		req.put("password", args[1]);
		req.put("version", ".1");
		req.put("client", "java uploader script");
		req.put("supported_types", 1);

		// Convert request to json
		String jsonOut = new JSONObject(req).toString();

		// Post it to json.ibroadcast.com
		JSONObject j = post(
				"https://json.ibroadcast.com/s/JSON/" + req.get("mode"),
				jsonOut);

		String userId, token;
		try {
			// Get user id and token from response
			JSONObject user = j.getJSONObject("user");
			userId = user.getString("id");
			token = user.getString("token");

		} catch (JSONException e) {
			error("Login failed. Please check your email address, password combination\n");
			// java compiler needs it
			return;
		}

		// convert the supported array to a map
		JSONArray supportedArray = j.getJSONArray("supported");

		Map<String, Boolean> supported = new HashMap<String, Boolean>();

		for (int i = 0; i < supportedArray.length(); i++) {
			JSONObject supportedExtension = supportedArray.getJSONObject(i);
			supported.put(supportedExtension.getString("extension"), true);
		}

		// current working directory
		String userDir = System.getProperty("user.dir");
		// Get files from current working directory
		Collection<File> listFileTree = listFileTree(new File(userDir),
				supported.keySet());
		// confirm upload with user
		boolean upload = confirm(listFileTree);
		// upload
		if (upload) {
			uploadFiles(listFileTree, userId, token);
		}
	}

	/**
	 * 
	 * @param files
	 *            music files found
	 */
	private static boolean confirm(Collection<File> files) {
		message("Found " + files.size()
				+ " files. Press 'L' to list, or 'U' to start the upload.\n");

		Pattern list = Pattern.compile("L", Pattern.CASE_INSENSITIVE);
		Pattern upload = Pattern.compile("U", Pattern.CASE_INSENSITIVE);

		Scanner sc = new Scanner(System.in);

		String userInput = sc.next();

		try {
			if (list.matcher(userInput).matches()) {
				message("\nListing found, supported files\n");
				for (File file : files) {
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
				message("Starting upload\n");
				return true;
			} else {
				message("aborted.\n");
				return false;
			}
		} finally {
			sc.close();
		}
	}

	/**
	 * 
	 * @param userId
	 * @param token
	 * @return
	 * @throws IOException
	 * @throws MalformedURLException
	 */
	private static Set<String> getMD5(String userId, String token)
			throws MalformedURLException, IOException {
		JSONObject md5Object = post("https://sync.ibroadcast.com", "user_id="
				+ userId + "&token=" + token,
				"application/x-www-form-urlencoded");
		JSONArray jsonArray = md5Object.getJSONArray("md5");
		Set<String> ret = new HashSet<String>();
		for (int i = 0; i < jsonArray.length(); i++) {
			try {
				String md5 = jsonArray.getString(i);
				ret.add(md5);
			} catch (JSONException e) {
			}
		}
		return ret;
	}

	/**
	 * Get supported media files from directory
	 * 
	 * @param dir
	 * @param extensions
	 *            supported media file extensions
	 * @return
	 */
	private static Collection<File> listFileTree(File dir,
			Set<String> extensions) {
		Set<File> fileTree = new HashSet<File>();
		for (File file : dir.listFiles()) {
			// skip hidden
			if (Pattern.compile("^\\..*").matcher(file.getName()).matches()) {
				continue;
			}
			if (file.isFile()) {
				// file extension
				Matcher extensionMatcher = Pattern.compile(".*(\\..{2,5})")
						.matcher(file.getName());
				if (extensionMatcher.matches()) {
					// file extension
					String extension = extensionMatcher.group(1);
					if (extensions.contains(extension)) {
						// add
						fileTree.add(file);
					}
				}
			} else {
				// dir, decend
				fileTree.addAll(listFileTree(file, extensions));
			}
		}
		return fileTree;
	}

	/**
	 * 
	 * @param listFileTree
	 * @param userId
	 * @param token
	 * @throws MalformedURLException
	 * @throws IOException
	 * @throws NoSuchAlgorithmException
	 */
	private static void uploadFiles(Collection<File> listFileTree,
			String userId, String token) throws MalformedURLException,
			IOException, NoSuchAlgorithmException {
		Set<String> md5 = getMD5(userId, token);
		MessageDigest md = MessageDigest.getInstance("MD5");
		byte[] buffer = new byte[8192];
		for (File file : listFileTree) {
			DigestInputStream dis = new DigestInputStream(new FileInputStream(
					file), md);

			try {
				while (dis.read(buffer) != -1)
					;
			} finally {
				dis.close();
			}
			// getting hex value of hash
			String hashText = new BigInteger(1, md.digest()).toString(16);
			// add leading zero, if not of length 32
			String hashTextPadded = hashText.length() == 32 ? hashText : "0"
					+ hashText;
			message("Uploading: " + file + "\n");
			if (md5.contains(hashText) || md5.contains(hashTextPadded)) {
				// already uploaded
				message(" skipping, already uploaded\n");
				continue;
			} else {
				int status = uploadFile("https://sync.ibroadcast.com", userId,
						token, file);
				if (status == HttpURLConnection.HTTP_OK) {
					message(" Done!\n");
				} else {
					message(" Failed.\n");
				}
			}
		}
	}

	/**
	 * Talk with iBroadcast, posting data
	 * 
	 * @param url
	 * @param content
	 *            post data
	 * @return response content, in json object format
	 * @throws IOException
	 * @throws MalformedURLException
	 */
	static private JSONObject post(String url, String content)
			throws MalformedURLException, IOException {
		return post(url, content, null);
	}

	/**
	 * Talk with iBroadcast, posting data
	 * 
	 * @param url
	 * @param content
	 *            post data
	 * @param contentType
	 * @return response content, in json object format
	 * @throws IOException
	 * @throws MalformedURLException
	 */
	static private JSONObject post(String url, String content,
			String contentType) throws MalformedURLException, IOException {
		HttpsURLConnection con = (HttpsURLConnection) new URL(url)
				.openConnection();

		// add request method
		con.setRequestMethod("POST");

		if (contentType != null) {
			con.setRequestProperty("Content-Type", contentType);
		}

		// Send post request
		con.setUseCaches(false);
		con.setDoOutput(true); // indicates POST method
		con.setDoInput(true);

		DataOutputStream wr = new DataOutputStream(con.getOutputStream());
		wr.writeBytes(content);
		wr.flush();
		wr.close();

		BufferedReader in = new BufferedReader(new InputStreamReader(
				con.getInputStream()));
		String inputLine;
		StringBuffer response = new StringBuffer();

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
	 * @param url
	 * @param userId
	 * @param token
	 * @param file
	 *            file to upload
	 * @return response code
	 * @throws IOException
	 * @throws MalformedURLException
	 * @throws Exception
	 */
	static private int uploadFile(String url, String userId, String token,
			File file) throws MalformedURLException, IOException {

		HttpsURLConnection con = (HttpsURLConnection) new URL(url)
				.openConnection();

		// add request method
		con.setRequestMethod("POST");

		// Send post request
		con.setUseCaches(false);
		con.setDoOutput(true); // indicates POST method
		con.setDoInput(true);

		// creates a unique boundary based on time stamp
		String boundary = "===" + System.currentTimeMillis() + "===";
		String contentType = "multipart/form-data; boundary=" + boundary;

		con.setRequestProperty("Content-Type", contentType);
		con.setRequestProperty("User-Agent", "java uploader");
		OutputStream outputStream = con.getOutputStream();
		PrintWriter writer = new PrintWriter(new OutputStreamWriter(
				outputStream, CHARSET), true);

		addFilePart(writer, outputStream, file, boundary);
		addParameter(writer, "file_path", file.getPath(), boundary);
		addParameter(writer, "method", "java uploader", boundary);
		addParameter(writer, "user_id", userId, boundary);
		addParameter(writer, "token", token, boundary);

		writer.append("--" + boundary + "--").append(CR_LF);
		writer.flush();
		writer.close();

		con.disconnect();

		return con.getResponseCode();
	}

	/**
	 * Adds a upload file section to the request
	 * 
	 * @param writer
	 * @param outputStream
	 * @param uploadFile
	 *            a File to be uploaded
	 * @param boundary
	 * @throws IOException
	 */
	private static void addFilePart(PrintWriter writer,
			OutputStream outputStream, File uploadFile, String boundary)
			throws IOException {
		String fileName = uploadFile.getName();
		writer.append("--" + boundary).append(CR_LF);
		String contentType = MimetypesFileTypeMap.getDefaultFileTypeMap()
				.getContentType(uploadFile);
		writer.append(
				"Content-Disposition: form-data; name=\"" + "file"
						+ "\"; filename=\"" + fileName + "\"").append(CR_LF);
		writer.append("Content-Type: " + contentType).append(CR_LF);
		writer.append(CR_LF);
		writer.flush();

		FileInputStream inputStream = new FileInputStream(uploadFile);
		byte[] buffer = new byte[4096];
		int bytesRead = -1;
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
	 * 
	 * @param writer
	 * @param name
	 *            parameter name
	 * @param value
	 *            parameter value
	 * @param boundary
	 */
	private static void addParameter(PrintWriter writer, String name,
			String value, String boundary) {
		writer.append("--" + boundary).append(CR_LF);
		writer.append("Content-Disposition: form-data; name=\"" + name + "\"")
				.append(CR_LF);
		writer.append(CR_LF);
		writer.append(value).append(CR_LF);
		writer.flush();
	}

	/**
	 * error message and exit
	 * 
	 * @param error
	 */
	private static void error(String error) {
		message(error);
		System.exit(1);
	}

	/**
	 * message
	 * 
	 * @param message
	 */
	private static void message(String message) {
		// an auto flush standard output stream
		PrintStream out = new PrintStream(System.out, true);

		out.print(message);
	}
}
