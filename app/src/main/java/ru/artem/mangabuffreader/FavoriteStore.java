package ru.artem.mangabuffreader;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class FavoriteStore {
    private static final String PREF_FAVORITES = "reader_favorites_v1";

    static final class Item {
        String slug = "";
        String title = "Без названия";
        String titleUrl = "";
        String posterUrl = "";
        String lastUrl = "";
        int volume;
        int chapter;
        int chapterProgress;
        int latestChapter;
        long updatedAt;

        Item copy() {
            Item copy = new Item();
            copy.slug = slug;
            copy.title = title;
            copy.titleUrl = titleUrl;
            copy.posterUrl = posterUrl;
            copy.lastUrl = lastUrl;
            copy.volume = volume;
            copy.chapter = chapter;
            copy.chapterProgress = chapterProgress;
            copy.latestChapter = latestChapter;
            copy.updatedAt = updatedAt;
            return copy;
        }

        int overallProgress() {
            if (latestChapter <= 0 || chapter <= 0) {
                return Math.max(0, Math.min(100, chapterProgress));
            }
            double completed = Math.max(0, chapter - 1) + Math.max(0, Math.min(100, chapterProgress)) / 100.0;
            return Math.max(0, Math.min(100, (int) Math.round(completed * 100.0 / latestChapter)));
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("slug", slug);
                json.put("title", title);
                json.put("titleUrl", titleUrl);
                json.put("posterUrl", posterUrl);
                json.put("lastUrl", lastUrl);
                json.put("volume", volume);
                json.put("chapter", chapter);
                json.put("chapterProgress", chapterProgress);
                json.put("latestChapter", latestChapter);
                json.put("updatedAt", updatedAt);
            } catch (Exception ignored) {
                // Все значения примитивные и безопасны для JSONObject.
            }
            return json;
        }

        static Item fromJson(JSONObject json) {
            Item item = new Item();
            item.slug = json.optString("slug", "").trim();
            item.title = json.optString("title", "Без названия").trim();
            item.titleUrl = json.optString("titleUrl", "").trim();
            item.posterUrl = json.optString("posterUrl", "").trim();
            item.lastUrl = json.optString("lastUrl", "").trim();
            item.volume = Math.max(0, json.optInt("volume", 0));
            item.chapter = Math.max(0, json.optInt("chapter", 0));
            item.chapterProgress = Math.max(0, Math.min(100, json.optInt("chapterProgress", 0)));
            item.latestChapter = Math.max(0, json.optInt("latestChapter", 0));
            item.updatedAt = Math.max(0L, json.optLong("updatedAt", 0L));
            return item;
        }
    }

    private final SharedPreferences preferences;

    FavoriteStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    synchronized List<Item> getAll() {
        List<Item> items = readAll();
        Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item first, Item second) {
                int byTime = Long.compare(second.updatedAt, first.updatedAt);
                if (byTime != 0) {
                    return byTime;
                }
                return first.title.compareToIgnoreCase(second.title);
            }
        });
        List<Item> copies = new ArrayList<>();
        for (Item item : items) {
            copies.add(item.copy());
        }
        return copies;
    }

    synchronized Item find(String slug) {
        if (slug == null || slug.trim().isEmpty()) {
            return null;
        }
        for (Item item : readAll()) {
            if (slug.equals(item.slug)) {
                return item.copy();
            }
        }
        return null;
    }

    synchronized boolean contains(String slug) {
        return find(slug) != null;
    }

    synchronized void upsert(Item incoming) {
        if (incoming == null || incoming.slug == null || incoming.slug.trim().isEmpty()) {
            return;
        }
        List<Item> items = readAll();
        Item target = null;
        for (Item item : items) {
            if (incoming.slug.equals(item.slug)) {
                target = item;
                break;
            }
        }
        if (target == null) {
            target = new Item();
            target.slug = incoming.slug;
            items.add(target);
        }

        if (incoming.title != null && !incoming.title.trim().isEmpty()) {
            target.title = incoming.title.trim();
        }
        if (incoming.titleUrl != null && !incoming.titleUrl.trim().isEmpty()) {
            target.titleUrl = incoming.titleUrl.trim();
        }
        if (incoming.posterUrl != null && !incoming.posterUrl.trim().isEmpty()) {
            target.posterUrl = incoming.posterUrl.trim();
        }
        if (incoming.lastUrl != null && !incoming.lastUrl.trim().isEmpty()) {
            target.lastUrl = incoming.lastUrl.trim();
        }
        boolean chapterChanged = incoming.chapter > 0 && incoming.chapter != target.chapter;
        if (incoming.volume > 0) {
            target.volume = incoming.volume;
        }
        if (incoming.chapter > 0) {
            target.chapter = incoming.chapter;
        }
        int previousProgress = chapterChanged ? 0 : target.chapterProgress;
        target.chapterProgress = Math.max(
                previousProgress,
                Math.max(0, Math.min(100, incoming.chapterProgress))
        );
        target.latestChapter = Math.max(target.latestChapter, incoming.latestChapter);
        target.updatedAt = incoming.updatedAt > 0 ? incoming.updatedAt : System.currentTimeMillis();
        writeAll(items);
    }

    synchronized void updateProgress(
            String slug,
            String lastUrl,
            int volume,
            int chapter,
            int progress
    ) {
        List<Item> items = readAll();
        boolean changed = false;
        for (Item item : items) {
            if (!item.slug.equals(slug)) {
                continue;
            }
            item.lastUrl = lastUrl == null ? item.lastUrl : lastUrl;
            if (volume > 0) {
                item.volume = volume;
            }
            if (chapter > 0 && chapter != item.chapter) {
                item.chapter = chapter;
                item.chapterProgress = 0;
            }
            item.chapterProgress = Math.max(0, Math.min(100, progress));
            item.updatedAt = System.currentTimeMillis();
            changed = true;
            break;
        }
        if (changed) {
            writeAll(items);
        }
    }

    synchronized void remove(String slug) {
        List<Item> items = readAll();
        for (int index = items.size() - 1; index >= 0; index--) {
            if (items.get(index).slug.equals(slug)) {
                items.remove(index);
            }
        }
        writeAll(items);
    }

    private List<Item> readAll() {
        List<Item> items = new ArrayList<>();
        String raw = preferences.getString(PREF_FAVORITES, "[]");
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            for (int index = 0; index < array.length(); index++) {
                JSONObject json = array.optJSONObject(index);
                if (json == null) {
                    continue;
                }
                Item item = Item.fromJson(json);
                if (!item.slug.isEmpty()) {
                    items.add(item);
                }
            }
        } catch (Exception ignored) {
            // Повреждённое локальное избранное не должно мешать запуску приложения.
        }
        return items;
    }

    private void writeAll(List<Item> items) {
        JSONArray array = new JSONArray();
        for (Item item : items) {
            array.put(item.toJson());
        }
        preferences.edit().putString(PREF_FAVORITES, array.toString()).apply();
    }
}
