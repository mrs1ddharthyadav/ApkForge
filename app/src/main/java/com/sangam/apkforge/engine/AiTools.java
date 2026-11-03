package com.sangam.apkforge.engine;

import com.reandroid.json.JSONArray;
import com.reandroid.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The tools Claude can call to look at and change the open APK.
 * Every result is plain text that Claude reads; errors are returned as text too, so Claude
 * can correct itself instead of the whole chat failing.
 */
public final class AiTools {

    private static final int MAX_RESULT_CHARS = 40_000;
    private static final int DEFAULT_LINES = 600;

    private final ApkProject project;
    /** Decoded text of files, so repeated searches don't decode every XML again. */
    private final Map<String, String> textCache = new HashMap<>();
    /** Plain-language list of changes made in this chat, newest last. */
    private final List<String> changes = new ArrayList<>();

    public AiTools(ApkProject project) {
        this.project = project;
    }

    public List<String> getChanges() {
        return changes;
    }

    /** Forgets cached file text, e.g. after the user edited files by hand. */
    public void clearCache() {
        textCache.clear();
    }

    // ------------------------------------------------------------------ definitions

    public static JSONArray definitions() {
        JSONArray tools = new JSONArray();
        tools.put(tool("list_files",
                "List the folders and files directly inside one folder of the APK. Use \"\" for the top level. "
                        + "Folders show how many files they contain.",
                props(prop("folder", "string", "Folder path such as \"\", \"res\" or \"res/layout\".")),
                new String[0]));
        tools.put(tool("find_files",
                "Find files whose path contains some text, for example \"activity_main\" or \"colors\".",
                props(prop("name_contains", "string", "Text to look for in file paths (not case sensitive).")),
                new String[]{"name_contains"}));
        tools.put(tool("read_file",
                "Read a text or XML file. Compiled Android XML (manifest, layouts, drawables, values) is shown as "
                        + "normal XML. Long files are returned in parts: use start_line to read further.",
                props(prop("path", "string", "File path, for example \"res/layout/activity_main.xml\"."),
                        prop("start_line", "integer", "First line to return, starting at 1. Default 1."),
                        prop("line_count", "integer", "How many lines to return. Default " + DEFAULT_LINES + ".")),
                new String[]{"path"}));
        tools.put(tool("search_text",
                "Search inside every text and XML file for some text (not case sensitive). "
                        + "Returns matching lines as path:line: text.",
                props(prop("query", "string", "Text to search for."),
                        prop("folder", "string", "Only search inside this folder, for example \"res/layout\". Optional.")),
                new String[]{"query"}));
        tools.put(tool("edit_file",
                "Replace text inside a text or XML file. old_text must match the file exactly (including spaces) "
                        + "and appear only once, unless replace_all is true. Read the file first.",
                props(prop("path", "string", "File to edit."),
                        prop("old_text", "string", "Exact text to replace."),
                        prop("new_text", "string", "Text to put in its place."),
                        prop("replace_all", "boolean", "Replace every occurrence. Default false.")),
                new String[]{"path", "old_text", "new_text"}));
        tools.put(tool("write_file",
                "Replace the whole content of an existing text or XML file. Can also create a NEW file, but only "
                        + "inside assets/. Prefer edit_file for small changes.",
                props(prop("path", "string", "File to write."),
                        prop("content", "string", "The complete new content.")),
                new String[]{"path", "content"}));
        tools.put(tool("delete_file",
                "Delete a file from the APK. Only do this when the user clearly asked for it.",
                props(prop("path", "string", "File to delete.")),
                new String[]{"path"}));
        tools.put(tool("list_strings",
                "List the app's text strings (from resources), with their values in one language.",
                props(prop("search", "string", "Only strings whose name or text contains this. Optional."),
                        prop("language", "string", "Language code like \"hi\" or \"pt-BR\". Empty for the default language."),
                        prop("offset", "integer", "Skip this many results, for reading long lists in parts. Default 0.")),
                new String[0]));
        JSONObject item = new JSONObject();
        item.put("type", "object");
        item.put("properties", props(prop("name", "string", "String name, e.g. \"app_name\"."),
                prop("value", "string", "New text.")));
        item.put("required", arr("name", "value"));
        JSONObject values = new JSONObject();
        values.put("type", "array");
        values.put("items", item);
        values.put("description", "Strings to set. Up to 150 per call.");
        JSONObject setProps = props(prop("language", "string",
                "Language code like \"hi\", \"es\" or \"pt-BR\". Empty for the default language. "
                        + "If the app has no version in this language yet, one is created (this is how to translate the app)."));
        setProps.put("values", values);
        tools.put(tool("set_strings",
                "Change the text of one or more strings in one language. Keep placeholders like %s, %d, %1$s "
                        + "and escape sequences like \\n exactly as they are.",
                setProps, new String[]{"language", "values"}));
        tools.put(tool("list_values",
                "List the app's simple resource values of one type: color (colors such as colorPrimary, "
                        + "background colors), dimen (sizes like 16dp / 14sp), bool (true/false switches) or integer. "
                        + "Values may differ per qualifier, e.g. -night for dark mode or -v21 for newer Android.",
                props(prop("type", "string", "One of: color, dimen, bool, integer."),
                        prop("search", "string", "Only names or values containing this. Optional."),
                        prop("offset", "integer", "Skip this many results, for reading long lists in parts. Default 0.")),
                new String[]{"type"}));
        JSONObject vItem = new JSONObject();
        vItem.put("type", "object");
        vItem.put("properties", props(prop("name", "string", "Resource name, e.g. \"colorPrimary\"."),
                prop("value", "string", "New value: #RRGGBB or #AARRGGBB for colors, 16dp or 14sp for dimen, "
                        + "true/false for bool, a whole number for integer, or a reference like @color/black."),
                prop("qualifier", "string", "Which version to change, e.g. \"-night\". Empty for the default. Optional.")));
        vItem.put("required", arr("name", "value"));
        JSONObject vValues = new JSONObject();
        vValues.put("type", "array");
        vValues.put("items", vItem);
        vValues.put("description", "Values to set. Up to 150 per call.");
        JSONObject vProps = props(prop("type", "string", "One of: color, dimen, bool, integer."));
        vProps.put("values", vValues);
        tools.put(tool("set_values",
                "Change one or more color, dimen, bool or integer resource values. Only existing names and "
                        + "existing qualifier versions can be changed.",
                vProps, new String[]{"type", "values"}));
        return tools;
    }

    private static JSONObject tool(String name, String description, JSONObject properties, String[] required) {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", arr(required));
        JSONObject t = new JSONObject();
        t.put("name", name);
        t.put("description", description);
        t.put("input_schema", schema);
        return t;
    }

    private static JSONObject props(JSONObject... ps) {
        JSONObject o = new JSONObject();
        for (JSONObject p : ps) o.put(p.getString("__name"), p.getJSONObject("__schema"));
        return o;
    }

    private static JSONObject prop(String name, String type, String description) {
        JSONObject schema = new JSONObject();
        schema.put("type", type);
        schema.put("description", description);
        JSONObject wrapper = new JSONObject();
        wrapper.put("__name", name);
        wrapper.put("__schema", schema);
        return wrapper;
    }

    private static JSONArray arr(String... items) {
        JSONArray a = new JSONArray();
        for (String s : items) a.put(s);
        return a;
    }

    // ------------------------------------------------------------------ descriptions for the UI

    /** Short, plain description of a tool call, shown in the chat while it runs. */
    public static String describe(String name, JSONObject in) {
        switch (name) {
            case "list_files": {
                String f = in.optString("folder", "");
                return f.isEmpty() ? "Looking at the APK's folders" : "Looking in " + f;
            }
            case "find_files": return "Finding files named \"" + in.optString("name_contains") + "\"";
            case "read_file": return "Reading " + in.optString("path");
            case "search_text": return "Searching for \"" + in.optString("query") + "\"";
            case "edit_file": return "Editing " + in.optString("path");
            case "write_file": return "Writing " + in.optString("path");
            case "delete_file": return "Deleting " + in.optString("path");
            case "list_strings": return "Reading the app's texts";
            case "set_strings": {
                JSONArray v = in.optJSONArray("values");
                int n = v == null ? 0 : v.length();
                return "Changing " + n + " text" + (n == 1 ? "" : "s") + " (" + ApkProject.describeQualifiers(safeQ(in.optString("language", ""))) + ")";
            }
            case "list_values": return "Reading the app's " + in.optString("type") + " values";
            case "set_values": {
                JSONArray v = in.optJSONArray("values");
                int n = v == null ? 0 : v.length();
                return "Changing " + n + " " + in.optString("type") + " value" + (n == 1 ? "" : "s");
            }
            default: return "Running " + name;
        }
    }

    private static String safeQ(String raw) {
        try {
            return ApkProject.normalizeQualifiers(raw);
        } catch (IOException e) {
            return raw;
        }
    }

    // ------------------------------------------------------------------ execution

    /** Result of one tool call. */
    public static final class Result {
        public final String text;
        public final boolean error;
        /** True if the APK was changed. */
        public final boolean changed;

        Result(String text, boolean error, boolean changed) {
            this.text = text;
            this.error = error;
            this.changed = changed;
        }
    }

    public Result run(String name, JSONObject in) {
        try {
            switch (name) {
                case "list_files": return ok(listFiles(in.optString("folder", "")));
                case "find_files": return ok(findFiles(in.getString("name_contains")));
                case "read_file": return ok(readFile(in.getString("path"), in.optInt("start_line", 1), in.optInt("line_count", DEFAULT_LINES)));
                case "search_text": return ok(searchText(in.getString("query"), in.optString("folder", "")));
                case "edit_file": return changed(editFile(in.getString("path"), in.getString("old_text"), in.getString("new_text"), in.optBoolean("replace_all", false)));
                case "write_file": return changed(writeFile(in.getString("path"), in.getString("content")));
                case "delete_file": return changed(deleteFile(in.getString("path")));
                case "list_strings": return ok(listStrings(in.optString("search", ""), in.optString("language", ""), in.optInt("offset", 0)));
                case "set_strings": return changed(setStrings(in.optString("language", ""), in.getJSONArray("values")));
                case "list_values": return ok(listValues(in.getString("type"), in.optString("search", ""), in.optInt("offset", 0)));
                case "set_values": return changed(setValues(in.getString("type"), in.getJSONArray("values")));
                default: return new Result("Unknown tool: " + name, true, false);
            }
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Result("Error: " + msg, true, false);
        } catch (OutOfMemoryError e) {
            return new Result("Error: the phone ran out of memory doing that. Try a smaller step.", true, false);
        }
    }

    private static Result ok(String text) {
        return new Result(limit(text), false, false);
    }

    private static Result changed(String text) {
        return new Result(limit(text), false, true);
    }

    private String listFiles(String folder) {
        String f = trimSlashes(folder);
        String prefix = f.isEmpty() ? "" : f + "/";
        TreeMap<String, Integer> dirs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<String> files = new ArrayList<>();
        for (ApkProject.FileItem item : project.listFiles()) {
            if (!item.path.startsWith(prefix)) continue;
            String rest = item.path.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash >= 0) {
                String d = rest.substring(0, slash);
                dirs.put(d, dirs.getOrDefault(d, 0) + 1);
            } else {
                files.add(rest + "  (" + kindLabel(item.kind) + ", " + item.size + " bytes)");
            }
        }
        if (dirs.isEmpty() && files.isEmpty()) return "No folder named \"" + f + "\". Use list_files with \"\" to see the top level.";
        StringBuilder sb = new StringBuilder("Folder: /" + f + "\n");
        for (Map.Entry<String, Integer> d : dirs.entrySet()) sb.append("[folder] ").append(d.getKey()).append("/  (").append(d.getValue()).append(" files)\n");
        int shown = 0;
        for (String s : files) {
            if (shown++ >= 400) {
                sb.append("... and ").append(files.size() - 400).append(" more files. Use find_files to narrow down.\n");
                break;
            }
            sb.append(s).append('\n');
        }
        return sb.toString();
    }

    private String findFiles(String text) {
        String q = text.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (ApkProject.FileItem item : project.listFiles()) {
            if (!item.path.toLowerCase(Locale.ROOT).contains(q)) continue;
            if (n++ >= 300) {
                sb.append("... more results not shown. Use a more specific name.\n");
                break;
            }
            sb.append(item.path).append("  (").append(kindLabel(item.kind)).append(")\n");
        }
        return n == 0 ? "No file paths contain \"" + text + "\"." : sb.toString();
    }

    private String readFile(String path, int startLine, int count) throws IOException {
        String text = textOf(path);
        String[] lines = text.split("\n", -1);
        int start = Math.max(1, startLine);
        int max = Math.max(1, Math.min(count, 3000));
        if (start > lines.length) return "The file has only " + lines.length + " lines.";
        int end = Math.min(lines.length, start + max - 1);
        StringBuilder sb = new StringBuilder();
        sb.append("File: ").append(path).append(" (lines ").append(start).append('-').append(end)
                .append(" of ").append(lines.length).append(")\n");
        for (int i = start - 1; i < end; i++) {
            sb.append(lines[i]).append('\n');
            if (sb.length() > MAX_RESULT_CHARS - 200) {
                sb.append("[cut here because it is long: continue with start_line=").append(i + 2).append("]\n");
                return sb.toString();
            }
        }
        if (end < lines.length) sb.append("[more lines follow: continue with start_line=").append(end + 1).append("]\n");
        return sb.toString();
    }

    private String searchText(String query, String folder) {
        String q = query.toLowerCase(Locale.ROOT);
        String f = trimSlashes(folder);
        String prefix = f.isEmpty() ? "" : f + "/";
        StringBuilder sb = new StringBuilder();
        int matches = 0;
        int skipped = 0;
        for (ApkProject.FileItem item : project.listFiles()) {
            if (!item.path.startsWith(prefix)) continue;
            if (item.kind != ApkProject.Kind.BINARY_XML && item.kind != ApkProject.Kind.TEXT) continue;
            if (item.size > 2_000_000) continue;
            String text;
            try {
                text = textOf(item.path);
            } catch (Exception e) {
                skipped++;
                continue;
            }
            if (!text.toLowerCase(Locale.ROOT).contains(q)) continue;
            String[] lines = text.split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].toLowerCase(Locale.ROOT).contains(q)) continue;
                String line = lines[i].trim();
                if (line.length() > 200) line = line.substring(0, 200) + "...";
                sb.append(item.path).append(':').append(i + 1).append(": ").append(line).append('\n');
                if (++matches >= 80) {
                    sb.append("... more matches not shown. Search for something more specific or use folder.\n");
                    return sb.toString();
                }
            }
        }
        if (matches == 0) return "No text or XML file contains \"" + query + "\"." + (skipped > 0 ? " (" + skipped + " files could not be read.)" : "");
        return sb.toString();
    }

    private String editFile(String path, String oldText, String newText, boolean all) throws IOException {
        if (oldText.isEmpty()) throw new IOException("old_text is empty.");
        String text = textOf(path);
        int count = countOf(text, oldText);
        if (count == 0) throw new IOException("old_text was not found in " + path + ". Read the file again and copy the text exactly.");
        if (count > 1 && !all) throw new IOException("old_text appears " + count + " times in " + path + ". Include more surrounding text so it is unique, or set replace_all.");
        String updated = all ? text.replace(oldText, newText) : replaceOnce(text, oldText, newText);
        project.writeText(path, updated);
        textCache.put(path, updated);
        changes.add("Edited " + path);
        return "Saved " + path + " (" + (all ? count + " replacements" : "1 replacement") + ").";
    }

    private String writeFile(String path, String content) throws IOException {
        String p = trimSlashes(path);
        if (project.exists(p)) {
            ApkProject.Kind kind = project.kindOf(p);
            if (kind != ApkProject.Kind.BINARY_XML && kind != ApkProject.Kind.TEXT) {
                throw new IOException(p + " is not a text file, so it can't be written as text.");
            }
            project.writeText(p, content);
            textCache.put(p, content);
            changes.add("Rewrote " + p);
            return "Saved " + p + ".";
        }
        if (!p.startsWith("assets/")) {
            throw new IOException("New files can only be created inside assets/. Files in res/ also need a resource "
                    + "entry, which this editor can't add yet. Change an existing file instead.");
        }
        project.addFile(p, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        textCache.put(p, content);
        changes.add("Created " + p);
        return "Created " + p + ".";
    }

    private String deleteFile(String path) throws IOException {
        String p = trimSlashes(path);
        project.deleteFile(p);
        textCache.remove(p);
        changes.add("Deleted " + p);
        return "Deleted " + p + ".";
    }

    private String listStrings(String search, String language, int offset) throws IOException {
        String q = ApkProject.normalizeQualifiers(language);
        String s = search.toLowerCase(Locale.ROOT);
        List<ApkProject.StringItem> all = project.listStrings();
        StringBuilder sb = new StringBuilder();
        int matched = 0;
        int shown = 0;
        int missing = 0;
        for (ApkProject.StringItem item : all) {
            String value = item.values.get(q);
            if (value == null) {
                missing++;
                if (!q.isEmpty()) {
                    // Still list it (with the default text) so a translation can be added.
                    value = null;
                } else {
                    continue;
                }
            }
            String hay = (item.name + " " + (value == null ? item.defaultValue() : value)).toLowerCase(Locale.ROOT);
            if (!s.isEmpty() && !hay.contains(s)) continue;
            matched++;
            if (matched <= offset) continue;
            if (shown >= 200 || sb.length() > MAX_RESULT_CHARS - 500) continue;
            shown++;
            if (value == null) {
                sb.append(item.name).append(" = [no ").append(ApkProject.describeQualifiers(q)).append(" text yet; default: ")
                        .append(oneLine(item.defaultValue())).append("]\n");
            } else {
                sb.append(item.name).append(" = ").append(oneLine(value)).append('\n');
            }
        }
        StringBuilder head = new StringBuilder();
        head.append("Language: ").append(ApkProject.describeQualifiers(q))
                .append(". Languages in this app: ");
        List<String> langs = project.listStringLanguages();
        for (int i = 0; i < langs.size() && i < 60; i++) {
            if (i > 0) head.append(", ");
            head.append(ApkProject.describeQualifiers(langs.get(i)));
        }
        head.append(".\nShowing ").append(shown).append(" of ").append(matched).append(" matching strings");
        if (offset > 0) head.append(" (starting after ").append(offset).append(")");
        head.append(".\n");
        if (offset + shown < matched) {
            sb.append("[more strings: call list_strings again with offset=").append(offset + shown).append("]\n");
        }
        return head.toString() + sb;
    }

    private String setStrings(String language, JSONArray values) throws IOException {
        String q = ApkProject.normalizeQualifiers(language);
        if (values.length() > 200) throw new IOException("Too many strings in one call. Send at most 150.");
        int done = 0;
        StringBuilder errors = new StringBuilder();
        for (int i = 0; i < values.length(); i++) {
            JSONObject v = values.optJSONObject(i);
            if (v == null) continue;
            String name = v.optString("name", "");
            try {
                project.setStringByName(name, q, v.optString("value", ""), true);
                done++;
            } catch (IOException e) {
                errors.append("  ").append(name).append(": ").append(e.getMessage()).append('\n');
            }
        }
        if (done > 0) changes.add("Changed " + done + " text" + (done == 1 ? "" : "s") + " (" + ApkProject.describeQualifiers(q) + ")");
        String result = "Set " + done + " of " + values.length() + " strings in " + ApkProject.describeQualifiers(q) + ".";
        if (errors.length() > 0) result += "\nProblems:\n" + errors;
        return result;
    }

    private String listValues(String type, String search, int offset) throws IOException {
        String s = search.toLowerCase(Locale.ROOT);
        List<ApkProject.ValueItem> all = project.listValues(type);
        StringBuilder sb = new StringBuilder();
        int matched = 0;
        int shown = 0;
        for (ApkProject.ValueItem item : all) {
            StringBuilder line = new StringBuilder(item.name).append(" =");
            for (Map.Entry<String, String> e : item.values.entrySet()) {
                line.append(' ');
                if (!e.getKey().isEmpty()) line.append('[').append(e.getKey()).append("] ");
                line.append(e.getValue()).append(';');
            }
            if (!s.isEmpty() && !line.toString().toLowerCase(Locale.ROOT).contains(s)) continue;
            matched++;
            if (matched <= offset) continue;
            if (shown >= 300 || sb.length() > MAX_RESULT_CHARS - 500) continue;
            shown++;
            sb.append(line).append('\n');
        }
        if (matched == 0) return "No " + type + " values" + (s.isEmpty() ? "" : " match \"" + search + "\"") + ".";
        String head = "Showing " + shown + " of " + matched + " " + type + " values"
                + (offset > 0 ? " (starting after " + offset + ")" : "") + ". [qualifier] marks a non-default version.\n";
        if (offset + shown < matched) sb.append("[more: call list_values again with offset=").append(offset + shown).append("]\n");
        return head + sb;
    }

    private String setValues(String type, JSONArray values) throws IOException {
        if (values.length() > 200) throw new IOException("Too many values in one call. Send at most 150.");
        int done = 0;
        StringBuilder errors = new StringBuilder();
        for (int i = 0; i < values.length(); i++) {
            JSONObject v = values.optJSONObject(i);
            if (v == null) continue;
            String name = v.optString("name", "");
            try {
                project.setValue(type, name, v.optString("qualifier", ""), v.optString("value", ""));
                done++;
            } catch (IOException e) {
                errors.append("  ").append(name).append(": ").append(e.getMessage()).append('\n');
            }
        }
        if (done > 0) changes.add("Changed " + done + " " + type + " value" + (done == 1 ? "" : "s"));
        String result = "Set " + done + " of " + values.length() + " " + type + " values.";
        if (errors.length() > 0) result += "\nProblems:\n" + errors;
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private String textOf(String path) throws IOException {
        String p = trimSlashes(path);
        String cached = textCache.get(p);
        if (cached != null) return cached;
        if (!project.exists(p) && !p.equals("AndroidManifest.xml")) {
            throw new IOException("No file at " + p + ". Use find_files to look up the right path.");
        }
        ApkProject.Kind kind = project.kindOf(p);
        switch (kind) {
            case BINARY_XML:
            case TEXT:
                String text = project.readText(p);
                textCache.put(p, text);
                return text;
            case IMAGE:
                throw new IOException(p + " is an image. Images can't be read or drawn as text.");
            case DEX:
                throw new IOException(p + " is compiled app code. Editing code isn't available yet in APK Forge.");
            case RESOURCE_TABLE:
                throw new IOException("resources.arsc is the resource table. Use list_strings and set_strings for texts.");
            default:
                throw new IOException(p + " is a binary file and can't be read as text.");
        }
    }

    private static String kindLabel(ApkProject.Kind k) {
        switch (k) {
            case BINARY_XML: return "xml";
            case TEXT: return "text";
            case IMAGE: return "image";
            case DEX: return "code";
            case RESOURCE_TABLE: return "resource table";
            default: return "binary";
        }
    }

    private static int countOf(String text, String part) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(part, i)) >= 0) {
            n++;
            i += part.length();
        }
        return n;
    }

    private static String replaceOnce(String text, String oldText, String newText) {
        int i = text.indexOf(oldText);
        return text.substring(0, i) + newText + text.substring(i + oldText.length());
    }

    private static String trimSlashes(String p) {
        String s = p == null ? "" : p.trim().replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String oneLine(String s) {
        String t = s.replace("\n", "\\n");
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }

    private static String limit(String s) {
        if (s.length() <= MAX_RESULT_CHARS) return s;
        return s.substring(0, MAX_RESULT_CHARS) + "\n[cut: result too long]";
    }
}
