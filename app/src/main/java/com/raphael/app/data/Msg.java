package com.raphael.app.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Msg {
    public static final String USER = "u", AI = "a";
    public String role;
    public String text;   // modele giden/gelen tam metin
    public String shown;  // ekranda gösterilen (boşsa text)
    public String site;   // cevabı veren kaynak
    public long ts;
    public List<String> imgs = new ArrayList<>(); // görsel dosya adları (files/imgs/)

    public Msg(String role, String text, String shown, String site) {
        this.role = role; this.text = text; this.shown = shown; this.site = site; this.ts = System.currentTimeMillis();
    }

    public String display() { return (shown == null || shown.isEmpty()) ? text : shown; }

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("r", role).put("t", text).put("ts", ts);
        if (shown != null && !shown.equals(text)) o.put("s", shown);
        if (site != null) o.put("w", site);
        if (!imgs.isEmpty()) o.put("i", new JSONArray(imgs));
        return o;
    }

    public static Msg fromJson(JSONObject o) {
        Msg m = new Msg(o.optString("r", USER), o.optString("t", ""), o.optString("s", null), o.optString("w", null));
        m.ts = o.optLong("ts", 0);
        JSONArray ia = o.optJSONArray("i");
        if (ia != null) for (int i = 0; i < ia.length(); i++) m.imgs.add(ia.optString(i));
        return m;
    }
}
