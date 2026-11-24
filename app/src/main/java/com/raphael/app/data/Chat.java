package com.raphael.app.data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Chat {
    public String id = UUID.randomUUID().toString().substring(0, 8);
    public String title = "Yeni sohbet";
    public String site = "gemini";
    public long created = System.currentTimeMillis();
    public long updated = created;
    public int count = 0; // sadece liste için
    public String codeDir = "";
    public boolean codeAuto = false;
    public final List<Msg> msgs = new ArrayList<>();
}
