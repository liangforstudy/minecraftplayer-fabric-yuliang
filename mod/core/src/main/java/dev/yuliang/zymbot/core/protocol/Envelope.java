package dev.yuliang.zymbot.core.protocol;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One bus message (FOUNDATION.md → Communication stack). Wire form, one line:
 * <pre>zb1 &lt;msgId&gt; &lt;sender&gt; &lt;serverId&gt; &lt;day&gt; &lt;x&gt;,&lt;z&gt; &lt;TYPE&gt; [fields...] #&lt;sig&gt;</pre>
 * The version tag lets old and new bots coexist; unknown types are ignored by receivers, not
 * errors. Fields are percent-encoded, so spaces and '#' can't break the framing. Position rides on
 * every message so a receiver can apply a virtual range.
 */
public record Envelope(String msgId, UUID sender, String serverId, long day, int x, int z,
                       String type, List<String> fields) {
    public static final String VERSION = "zb1";

    public Envelope {
        fields = List.copyOf(fields);
    }

    public String field(int i) {
        return i < fields.size() ? fields.get(i) : "";
    }

    /** The signed part — everything except the signature. */
    public String body() {
        StringBuilder sb = new StringBuilder(VERSION).append(' ').append(msgId).append(' ').append(sender)
                .append(' ').append(serverId).append(' ').append(day).append(' ').append(x).append(',').append(z)
                .append(' ').append(type);
        for (String f : fields) sb.append(' ').append(enc(f));
        return sb.toString();
    }

    public String encode(Signer signer) {
        String body = body();
        return body + " #" + signer.sign(body);
    }

    /** Parses and verifies. Empty if it isn't ours, is malformed, or the signature is wrong. */
    public static Optional<Envelope> decode(String line, Signer signer) {
        if (line == null) return Optional.empty();
        String s = line.strip();
        if (!s.startsWith(VERSION + " ")) return Optional.empty();
        int hash = s.lastIndexOf(" #");
        if (hash < 0) return Optional.empty();
        String body = s.substring(0, hash), sig = s.substring(hash + 2);
        if (!signer.verify(body, sig)) return Optional.empty();
        String[] p = body.split(" ");
        if (p.length < 7) return Optional.empty();
        try {
            String[] xz = p[5].split(",");
            List<String> fields = new ArrayList<>();
            for (int i = 7; i < p.length; i++) fields.add(p[i].equals("~") ? "" : URLDecoder.decode(p[i], StandardCharsets.UTF_8));
            return Optional.of(new Envelope(p[1], UUID.fromString(p[2]), p[3], Long.parseLong(p[4]),
                    Integer.parseInt(xz[0]), Integer.parseInt(xz[1]), p[6], fields));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String enc(String f) {
        // "~" marks an empty field; URLEncoder always escapes a literal '~' as %7E, so it can't collide
        return f.isEmpty() ? "~" : URLEncoder.encode(f, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
