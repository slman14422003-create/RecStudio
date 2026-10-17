package com.rec.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class Cloud {

    static final String BASE = "https://snowy-field-1de7.slman2233445566y.workers.dev/";
    static final String STT_MODEL = "whisper-large-v3-turbo";
    static final String LLM_MODEL = "llama-3.3-70b";
    static final String KEYS_URL = "https://dash.cloudflare.com/";
    static final int MAX_BYTES = 2 * 60 * Voice16k.RATE * 2;

    private static final String SYSTEM_FIX = "You correct speech recognition errors in Arabic transcripts. "
            + "The speaker may use Egyptian, Levantine or any other dialect: keep the dialect and the original wording. "
            + "Never rewrite, translate, summarize, censor or add content. "
            + "Fix every word that is misheard or makes no sense in the sentence: similar sounding wrong words, wrong names, broken words, wrong prepositions. Add punctuation. "
            + "If a line looks right, return it unchanged. "
            + "Reply with a JSON object {\"lines\": [...]} holding exactly the same number of strings in the same order.";

    static volatile String lastError = "";
    static volatile String fallbackNote = "";
    static volatile String enhanceNote = "";

    private Cloud() {}

    static String explain(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.length() > 160 ? m.substring(0, 160) : m;
    }

    static void ping(String key) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(BASE + "ping").openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(20000);
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setRequestProperty("Authorization", "Bearer " + key);
        con.setRequestProperty("Content-Type", "application/json");
        try {
            try (OutputStream out = con.getOutputStream()) {
                out.write("{}".getBytes(StandardCharsets.UTF_8));
            }
            if (con.getResponseCode() != 200) throw failure(con);
        } finally {
            con.disconnect();
        }
    }

    static String summarize(String key, String text) throws IOException {
        if (text.length() > 24000) text = text.substring(0, 24000);
        JSONObject req = new JSONObject();
        try {
            req.put("model", LLM_MODEL);
            req.put("temperature", 0.2);
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "system").put("content",
                    "Summarize the Arabic transcript in the same language and dialect. Output: a one-line title, then a short summary of 2-3 sentences, then up to 6 bullet points of key points, then action items or dates if any. Do not invent facts."));
            msgs.put(new JSONObject().put("role", "user").put("content", text));
            req.put("messages", msgs);
        } catch (JSONException e) {
            throw new IOException("bad request");
        }
        HttpURLConnection con = (HttpURLConnection) new URL(BASE + "chat/completions").openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(120000);
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setRequestProperty("Authorization", "Bearer " + key);
        con.setRequestProperty("Content-Type", "application/json");
        try {
            try (OutputStream out = con.getOutputStream()) {
                out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (con.getResponseCode() != 200) throw failure(con);
            return new JSONObject(readAll(con.getInputStream())).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();
        } catch (JSONException e) {
            throw new IOException("bad response");
        } finally {
            con.disconnect();
        }
    }

    static byte[] wav(byte[] pcm, int len) {
        int rate = Voice16k.RATE;
        ByteArrayOutputStream o = new ByteArrayOutputStream(len + 44);
        writeStr(o, "RIFF");
        writeInt(o, 36 + len);
        writeStr(o, "WAVEfmt ");
        writeInt(o, 16);
        writeShort(o, 1);
        writeShort(o, 1);
        writeInt(o, rate);
        writeInt(o, rate * 2);
        writeShort(o, 2);
        writeShort(o, 16);
        writeStr(o, "data");
        writeInt(o, len);
        o.write(pcm, 0, len);
        return o.toByteArray();
    }

    private static void writeStr(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        o.write(b, 0, b.length);
    }

    private static void writeInt(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
        o.write((v >> 16) & 0xff);
        o.write((v >> 24) & 0xff);
    }

    private static void writeShort(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[16 * 1024];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return new String(o.toByteArray(), StandardCharsets.UTF_8);
    }

    private static IOException failure(HttpURLConnection con) throws IOException {
        int code = con.getResponseCode();
        String body = readAll(con.getErrorStream());
        String msg = "";
        try {
            msg = new JSONObject(body).getJSONObject("error").optString("message", "");
        } catch (JSONException ignored) {
        }
        if (code == 401) return new IOException("HTTP 401 invalid key");
        if (code == 429) return new IOException("HTTP 429 limit reached, try later");
        return new IOException("HTTP " + code + (msg.isEmpty() ? "" : " " + msg));
    }

    static List<Transcript.Seg> transcribe(String key, byte[] wav, String lang, String prompt, long offsetMs) throws IOException {
        IOException last = null;
        for (int i = 0; i < 3; i++) {
            try {
                return transcribeOnce(key, wav, lang, prompt, offsetMs);
            } catch (IOException e) {
                last = e;
                String m = String.valueOf(e.getMessage());
                if (m.contains("401") || m.contains("413") || m.contains("400")) throw e;
                try {
                    Thread.sleep(2500L * (i + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    private static List<Transcript.Seg> transcribeOnce(String key, byte[] wav, String lang, String prompt, long offsetMs) throws IOException {
        String boundary = "rec" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection con = (HttpURLConnection) new URL(BASE + "audio/transcriptions").openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(180000);
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setChunkedStreamingMode(64 * 1024);
        con.setRequestProperty("Authorization", "Bearer " + key);
        con.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try {
            try (OutputStream out = con.getOutputStream()) {
                field(out, boundary, "model", STT_MODEL);
                field(out, boundary, "language", lang);
                field(out, boundary, "response_format", "verbose_json");
                field(out, boundary, "temperature", "0");
                if (prompt != null && !prompt.isEmpty()) field(out, boundary, "prompt", prompt);
                String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.UTF_8));
                out.write(wav);
                out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            int code = con.getResponseCode();
            if (code != 200) throw failure(con);
            String body = readAll(con.getInputStream());
            return parse(body, offsetMs);
        } finally {
            con.disconnect();
        }
    }

    private static void field(OutputStream out, String boundary, String name, String value) throws IOException {
        String s = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n";
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }

    private static List<Transcript.Seg> parse(String body, long offsetMs) throws IOException {
        List<Transcript.Seg> out = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(body);
            JSONArray segs = o.optJSONArray("segments");
            if (segs == null || segs.length() == 0) {
                String text = o.optString("text", "").trim();
                if (!text.isEmpty()) out.add(new Transcript.Seg(offsetMs, offsetMs, text));
                return out;
            }
            String prev = "";
            for (int i = 0; i < segs.length(); i++) {
                JSONObject s = segs.getJSONObject(i);
                String text = s.optString("text", "").trim();
                if (text.isEmpty() || text.equals(prev)) continue;
                if (s.optDouble("no_speech_prob", 0) > 0.7) continue;
                prev = text;
                long st = offsetMs + (long) (s.optDouble("start", 0) * 1000);
                long en = offsetMs + (long) (s.optDouble("end", 0) * 1000);
                out.add(new Transcript.Seg(st, en, text));
            }
        } catch (JSONException e) {
            throw new IOException("bad response");
        }
        return out;
    }

    static String[] fix(String key, String[] lines, String hint) throws IOException {
        JSONObject req = new JSONObject();
        try {
            req.put("model", LLM_MODEL);
            req.put("temperature", 0);
            req.put("response_format", new JSONObject().put("type", "json_object"));
            JSONArray msgs = new JSONArray();
            String sys = SYSTEM_FIX;
            if (hint != null && !hint.isEmpty()) sys = sys + " Known names and terms that may appear: " + hint + ".";
            msgs.put(new JSONObject().put("role", "system").put("content", sys));
            JSONArray in = new JSONArray();
            for (String l : lines) in.put(l);
            msgs.put(new JSONObject().put("role", "user").put("content", new JSONObject().put("lines", in).toString()));
            req.put("messages", msgs);
        } catch (JSONException e) {
            throw new IOException("bad request");
        }
        HttpURLConnection con = (HttpURLConnection) new URL(BASE + "chat/completions").openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(90000);
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setRequestProperty("Authorization", "Bearer " + key);
        con.setRequestProperty("Content-Type", "application/json");
        try {
            try (OutputStream out = con.getOutputStream()) {
                out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (con.getResponseCode() != 200) throw failure(con);
            String body = readAll(con.getInputStream());
            String content = new JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
            JSONArray arr = new JSONObject(content).getJSONArray("lines");
            if (arr.length() != lines.length) return lines;
            String[] out = new String[lines.length];
            for (int i = 0; i < out.length; i++) {
                String c = arr.optString(i, lines[i]).trim();
                out[i] = c.isEmpty() || Vocab.ratio(lines[i], c) < 0.55f ? lines[i] : c;
            }
            return out;
        } catch (JSONException e) {
            throw new IOException("bad response");
        } finally {
            con.disconnect();
        }
    }
}
