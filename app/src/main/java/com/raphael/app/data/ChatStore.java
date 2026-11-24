package com.raphael.app.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Sohbet geçmişi: files/chats/<id>.json + index.json (hafif liste). */
public class ChatStore {
    private final File dir, indexFile, imgDir;

    public ChatStore(Context c) {
        imgDir = new File(c.getFilesDir(), "imgs");
        imgDir.mkdirs();
        dir = new File(c.getFilesDir(), "chats");
        dir.mkdirs();
        indexFile = new File(dir, "index.json");
    }

    private static String read(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[16384]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) { return null; }
    }

    private static void write(File f, String s) {
        try {
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(s.getBytes("UTF-8")); }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
        } catch (Exception ignored) {}
    }

    private JSONArray readIndex() {
        String s = read(indexFile);
        try { if (s != null) return new JSONArray(s); } catch (Exception ignored) {}
        return new JSONArray();
    }

    public List<Chat> list() {
        List<Chat> out = new ArrayList<>();
        JSONArray a = readIndex();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Chat c = new Chat();
            c.id = o.optString("id"); c.title = o.optString("title"); c.site = o.optString("site", "gemini");
            c.updated = o.optLong("u"); c.count = o.optInt("n");
            out.add(c);
        }
        Collections.sort(out, new Comparator<Chat>() { @Override public int compare(Chat x, Chat y) { return Long.compare(y.updated, x.updated); } });
        return out;
    }

    public Chat load(String id) {
        String s = read(new File(dir, id + ".json"));
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            Chat c = new Chat();
            c.id = o.optString("id", id); c.title = o.optString("title", "Sohbet"); c.site = o.optString("site", "gemini");
            c.created = o.optLong("c"); c.updated = o.optLong("u");
            c.codeDir = o.optString("cd", ""); c.codeAuto = o.optBoolean("ca", false);
            JSONArray m = o.optJSONArray("m");
            if (m != null) for (int i = 0; i < m.length(); i++) c.msgs.add(Msg.fromJson(m.getJSONObject(i)));
            c.count = c.msgs.size();
            return c;
        } catch (Exception e) { return null; }
    }

    public void save(Chat c) {
        try {
            c.updated = System.currentTimeMillis();
            c.count = c.msgs.size();
            JSONObject o = new JSONObject();
            o.put("id", c.id).put("title", c.title).put("site", c.site).put("c", c.created).put("u", c.updated);
            if (c.codeDir != null && !c.codeDir.isEmpty()) o.put("cd", c.codeDir);
            if (c.codeAuto) o.put("ca", true);
            JSONArray m = new JSONArray();
            for (Msg x : c.msgs) m.put(x.toJson());
            o.put("m", m);
            write(new File(dir, c.id + ".json"), o.toString());

            JSONArray idx = readIndex(), n = new JSONArray();
            for (int i = 0; i < idx.length(); i++) { JSONObject e = idx.optJSONObject(i); if (e != null && !c.id.equals(e.optString("id"))) n.put(e); }
            n.put(new JSONObject().put("id", c.id).put("title", c.title).put("site", c.site).put("u", c.updated).put("n", c.count));
            write(indexFile, n.toString());
        } catch (Exception ignored) {}
    }

    public File img(String name) { return new File(imgDir, new File(name).getName()); }

    private void dropImages(Chat c) {
        if (c == null) return;
        for (Msg m : c.msgs) for (String n : m.imgs) img(n).delete();
    }

    public void delete(String id) {
        dropImages(load(id));
        new File(dir, id + ".json").delete();
        JSONArray idx = readIndex(), n = new JSONArray();
        for (int i = 0; i < idx.length(); i++) { JSONObject e = idx.optJSONObject(i); if (e != null && !id.equals(e.optString("id"))) n.put(e); }
        write(indexFile, n.toString());
    }

    public void deleteAll() {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        File[] is = imgDir.listFiles();
        if (is != null) for (File f : is) f.delete();
    }
}
