package com.raphael.app.core;

/** Desteklenen web siteleri. Yeni site eklemek = buraya bir satır + assets/js/<site>.js */
public enum Site {
    GEMINI("gemini", "Gemini", "gemini.google.com", "https://gemini.google.com/app", "js/gemini.js", false),
    AISTUDIO("aistudio", "AI Studio", "aistudio.google.com", "https://aistudio.google.com/prompts/new_chat", "js/aistudio.js", true);

    public final String id, label, host, newChatUrl, script;
    public final boolean modelPicker;

    Site(String id, String label, String host, String newChatUrl, String script, boolean modelPicker) {
        this.id = id; this.label = label; this.host = host;
        this.newChatUrl = newChatUrl; this.script = script; this.modelPicker = modelPicker;
    }

    public static Site fromId(String id) {
        for (Site s : values()) if (s.id.equals(id)) return s;
        return GEMINI;
    }
}
