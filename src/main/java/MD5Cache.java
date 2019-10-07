import org.json.JSONObject;
import org.json.JSONTokener;
import org.json.JSONWriter;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;

public class MD5Cache {

    private static final char[] DIGITS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};
    private static final int bufferSize = 32 * 4096;

    private static final ThreadLocal<MessageDigest> md5Digest = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    });

    private final ConcurrentHashMap<String, MD5Info> cache = new ConcurrentHashMap<>();

    String getMD5Sum(File file, String relativePath) {
        return cache.compute(relativePath, (key, existingInfo) -> {
            if (existingInfo == null || existingInfo.lastModified != file.lastModified()) {
                return new MD5Info(md5sumUnchecked(file), file.lastModified());
            } else {
                return existingInfo;
            }
        }).md5;
    }

    /**
     * load cache from JSON file. Return empty cache if file doesn't exist.
     */
    static MD5Cache load(File file) throws IOException {
        if (!file.exists())
            return new MD5Cache();
        try (var reader = new BufferedReader(new FileReader(file, UTF_8))) {
            var tokenizer = new JSONTokener(reader);
            var json = new JSONObject(tokenizer);
            var cache = new MD5Cache();
            json.keySet().forEach(key -> {
                var obj = json.getJSONObject(key);
                cache.cache.put(key, MD5Info.fromJSON(obj));
            });
            return cache;
        }
    }

    /**
     * save cache to a JSON file
     */
    void save(File file) throws IOException {
        try (var fileWriter = new BufferedWriter(new FileWriter(file, UTF_8))) {
            var jsonWriter = new JSONWriter(fileWriter);
            jsonWriter.object();
            cache.forEach((key, value) -> {
                jsonWriter.key(key);
                jsonWriter.value(value.toJSON());
            });
            jsonWriter.endObject();
        }
    }

    private static String md5sumUnchecked(File file) {
        try {
            return md5sum(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String md5sum(File file) throws IOException {
        var buffer = new byte[bufferSize];
        var digest = md5Digest.get();
        try (var dis = new DigestInputStream(new FileInputStream(file), digest)) {
            while (dis.read(buffer) != -1) { /*intentionally left blank*/ }
        }
        return encodeHex(digest.digest());
    }

    private static String encodeHex(byte[] data) {
        var l = data.length;
        var out = new char[l << 1];
        for (int i = 0, j = 0; i < l; i++) {
            out[j++] = DIGITS[(0xF0 & data[i]) >>> 4];
            out[j++] = DIGITS[0x0F & data[i]];
        }
        return new String(out);
    }


    private static class MD5Info {
        String md5;
        long lastModified;

        MD5Info(String md5, long lastModified) {
            this.md5 = md5;
            this.lastModified = lastModified;
        }

        JSONObject toJSON() {
            var json = new JSONObject();
            json.put("md5", md5);
            json.put("mod", lastModified);
            return json;
        }

        static MD5Info fromJSON(JSONObject json) {
            return new MD5Info(json.getString("md5"), json.getLong("mod"));
        }
    }


    public static void main(String[] args) throws IOException {
        var cache = new MD5Cache();
        var file = new File("/home/dmoebius/Musik/african/artists/Francis Bebey/2014 - Psychedelic Sanza 1982 - 1984/01-11 - Francis Bebey - Sanza Nocturne.mp3");
        var md5 = cache.getMD5Sum(file, "african/artists/Francis Bebey/2014 - Psychedelic Sanza 1982 - 1984/01-11 - Francis Bebey - Sanza Nocturne.mp3");
        var md5_2 = cache.getMD5Sum(file, "african/artists/Francis Bebey/2014 - Psychedelic Sanza 1982 - 1984/01-11 - Francis Bebey - Sanza Nocturne.mp3");
        var md5_3 = cache.getMD5Sum(file, "african/artists/Francis Bebey/2014 - Psychedelic Sanza 1982 - 1984/02-11 - Francis Bebey - Bissau.mp3");
        var md5_4 = cache.getMD5Sum(file, "african/artists/Francis Bebey/2014 - Psychedelic Sanza 1982 - 1984/02-11 - Francis Bebey - Bissau.mp3");
        System.out.println(md5);
        System.out.println(md5_2);
        System.out.println(md5_3);
        System.out.println(md5_4);
        var cacheFile = new File("/home/dmoebius/tmp/cache.json");
        cache.save(cacheFile);
        var cache2 = MD5Cache.load(cacheFile);
        System.out.println(cache);
        System.out.println(cache2);
    }
}
