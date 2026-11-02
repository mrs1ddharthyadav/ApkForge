package com.sangam.apkforge.engine;

import com.reandroid.apk.ApkModule;
import com.reandroid.archive.ByteInputSource;
import com.reandroid.archive.InputSource;
import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlDocument;
import com.reandroid.arsc.model.ResourceEntry;
import com.reandroid.arsc.value.DataType;
import com.reandroid.arsc.value.Entry;
import com.reandroid.arsc.value.ResValue;
import com.reandroid.xml.XMLFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * An APK opened for full editing. Changes are kept in memory until [build] writes a new APK.
 * Like ApkEditEngine, the output is UNSIGNED; sign it afterwards.
 *
 * Not thread safe: use it from one background thread at a time.
 */
public final class ApkProject implements Closeable {

    public enum Kind {
        /** Android's compiled XML (manifest, layouts...). Shown and edited as normal XML text. */
        BINARY_XML,
        /** Plain text such as .json, .txt, .html, .js. */
        TEXT,
        IMAGE,
        /** Compiled code. Viewed and edited in stage 4 (smali). */
        DEX,
        /** resources.arsc: edit it through the Strings tab instead. */
        RESOURCE_TABLE,
        OTHER
    }

    public static final class FileItem {
        public final String path;
        public final long size;
        public final Kind kind;

        FileItem(String path, long size, Kind kind) {
            this.path = path;
            this.size = size;
            this.kind = kind;
        }
    }

    /** One string resource and its text in every language it has. */
    public static final class StringItem {
        public final int id;
        public final String name;
        /** Key "" is the default text; other keys are qualifiers like "-hi" or "-es-rMX". */
        public final Map<String, String> values;

        StringItem(int id, String name, Map<String, String> values) {
            this.id = id;
            this.name = name;
            this.values = values;
        }

        public String defaultValue() {
            String v = values.get("");
            if (v != null) return v;
            return values.isEmpty() ? "" : values.values().iterator().next();
        }
    }

    private static final Set<String> TEXT_EXTENSIONS = new HashSet<>(Arrays.asList(
            "txt", "json", "xml", "html", "htm", "js", "css", "properties", "md", "csv", "ini",
            "cfg", "conf", "yml", "yaml", "version", "pro", "kt", "java", "svg", "smali", "gradle", "list"));
    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "png", "jpg", "jpeg", "webp", "gif", "bmp"));

    private static final int MAX_READ = 256 * 1024 * 1024;

    private final ApkModule module;
    private final File source;
    private boolean modified;

    private ApkProject(ApkModule module, File source) {
        this.module = module;
        this.source = source;
    }

    public static ApkProject open(File apk) throws IOException {
        ApkModule module = ApkModule.loadApkFile(apk);
        if (!module.hasAndroidManifest()) {
            module.close();
            throw new IOException("This file has no AndroidManifest.xml, so it is not an APK.");
        }
        return new ApkProject(module, apk);
    }

    public File getSource() {
        return source;
    }

    public boolean isModified() {
        return modified;
    }

    public String getPackageName() {
        return module.getAndroidManifest().getPackageName();
    }

    // ------------------------------------------------------------------ files

    public List<FileItem> listFiles() {
        List<FileItem> list = new ArrayList<>();
        for (InputSource s : module.getInputSources()) {
            long size;
            try {
                size = s.getLength();
            } catch (Exception e) {
                size = -1;
            }
            list.add(new FileItem(s.getAlias(), size, kindOf(s.getAlias())));
        }
        Collections.sort(list, (a, b) -> a.path.compareToIgnoreCase(b.path));
        return list;
    }

    public Kind kindOf(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        String ext = lower.contains(".") ? lower.substring(lower.lastIndexOf('.') + 1) : "";
        if (lower.equals("resources.arsc")) return Kind.RESOURCE_TABLE;
        if (ext.equals("dex")) return Kind.DEX;
        if (IMAGE_EXTENSIONS.contains(ext)) return Kind.IMAGE;
        if (ext.equals("xml") || lower.equals("androidmanifest.xml")) {
            return isBinaryXml(path) ? Kind.BINARY_XML : Kind.TEXT;
        }
        if (TEXT_EXTENSIONS.contains(ext)) return Kind.TEXT;
        return Kind.OTHER;
    }

    private boolean isBinaryXml(String path) {
        if (path.equals(AndroidManifestBlock.FILE_NAME)) return true;
        InputSource s = module.getInputSource(path);
        if (s == null) return false;
        // Compiled XML starts with chunk type 0x0003 and header size 0x0008 (little endian).
        try (InputStream in = s.openStream()) {
            byte[] head = new byte[4];
            int n = 0;
            while (n < 4) {
                int r = in.read(head, n, 4 - n);
                if (r < 0) break;
                n += r;
            }
            return n == 4 && head[0] == 3 && head[1] == 0 && head[2] == 8 && head[3] == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean exists(String path) {
        return module.getInputSource(path) != null;
    }

    public byte[] readBytes(String path) throws IOException {
        if (path.equals(AndroidManifestBlock.FILE_NAME)) {
            return module.getAndroidManifest().getBytes();
        }
        InputSource s = module.getInputSource(path);
        if (s == null) throw new IOException(path + " was not found.");
        // Note: InputSource.getBytes(n) always returns exactly n bytes, so read the stream ourselves.
        try (InputStream in = s.openStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                if (out.size() > MAX_READ) throw new IOException(path + " is too large to open.");
            }
            return out.toByteArray();
        }
    }

    /** Returns the file as editable text. Compiled XML is turned back into readable XML. */
    public String readText(String path) throws IOException {
        Kind kind = kindOf(path);
        if (kind == Kind.BINARY_XML) {
            try {
                ResXmlDocument doc = path.equals(AndroidManifestBlock.FILE_NAME)
                        ? module.getAndroidManifest()
                        : module.loadResXmlDocument(path);
                if (doc.getPackageBlock() == null) doc.setPackageBlock(packageBlock());
                return doc.serializeToXml();
            } catch (Exception e) {
                throw new IOException("Could not decode this XML file: " + e.getMessage(), e);
            }
        }
        if (kind == Kind.TEXT) {
            return new String(readBytes(path), StandardCharsets.UTF_8);
        }
        throw new IOException("This file is not text.");
    }

    /**
     * Saves edited text. Compiled XML files are compiled again from the text, so a typo
     * in the XML is reported here instead of breaking the app later.
     */
    public void writeText(String path, String text) throws IOException {
        Kind kind = kindOf(path);
        if (kind == Kind.BINARY_XML) {
            PackageBlock pkg = packageBlock();
            try {
                if (path.equals(AndroidManifestBlock.FILE_NAME)) {
                    AndroidManifestBlock manifest = new AndroidManifestBlock();
                    manifest.setPackageBlock(pkg);
                    manifest.parse(XMLFactory.newPullParser(text));
                    module.setManifest(manifest);
                } else {
                    ResXmlDocument doc = new ResXmlDocument();
                    doc.setPackageBlock(pkg);
                    doc.parse(XMLFactory.newPullParser(text));
                    putBytes(path, doc.getBytes());
                }
            } catch (Exception e) {
                throw new IOException(friendlyXmlError(e), e);
            }
        } else if (kind == Kind.TEXT) {
            putBytes(path, text.getBytes(StandardCharsets.UTF_8));
        } else {
            throw new IOException("This file is not text.");
        }
        modified = true;
    }

    /** Replaces a file with new content, keeping its compression setting. */
    public void replaceFile(String path, byte[] bytes) throws IOException {
        if (path.equals(AndroidManifestBlock.FILE_NAME) || path.equals("resources.arsc")) {
            throw new IOException("Edit " + path + " from its own editor instead of replacing it.");
        }
        if (!exists(path)) throw new IOException(path + " was not found.");
        putBytes(path, bytes);
        modified = true;
    }

    /** Adds a new file. Fails if one already exists at that path. */
    public void addFile(String path, byte[] bytes) throws IOException {
        String clean = cleanPath(path);
        if (exists(clean)) throw new IOException(clean + " already exists. Replace it instead.");
        module.add(new ByteInputSource(bytes, clean));
        modified = true;
    }

    public void deleteFile(String path) throws IOException {
        if (path.equals(AndroidManifestBlock.FILE_NAME) || path.equals("resources.arsc")) {
            throw new IOException(path + " is required and can't be deleted.");
        }
        if (module.removeInputSource(path) == null) throw new IOException(path + " was not found.");
        modified = true;
    }

    private void putBytes(String path, byte[] bytes) {
        InputSource old = module.getInputSource(path);
        ByteInputSource fresh = new ByteInputSource(bytes, path);
        if (old != null) {
            fresh.copyAttributes(old);
            module.removeInputSource(path);
        }
        module.add(fresh);
    }

    // ------------------------------------------------------------------ strings

    public List<StringItem> listStrings() {
        List<StringItem> out = new ArrayList<>();
        PackageBlock pkg = packageBlockOrNull();
        if (pkg == null) return out;
        Iterator<ResourceEntry> it = pkg.getResources("string");
        while (it.hasNext()) {
            ResourceEntry re = it.next();
            Map<String, String> values = new LinkedHashMap<>();
            for (Entry e : re) {
                ResValue v = e.getResValue();
                if (v == null || v.getDataType() != DataType.STRING) continue;
                String text = v.getDataAsString();
                if (text == null) continue;
                values.put(qualifiersOf(e), text);
            }
            if (!values.isEmpty()) out.add(new StringItem(re.getResourceId(), re.getName(), values));
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** All language/region qualifiers used by strings, "" (default) first. */
    public List<String> listStringLanguages() {
        Set<String> set = new TreeSet<>();
        for (StringItem s : listStrings()) set.addAll(s.values.keySet());
        List<String> list = new ArrayList<>(set);
        if (list.remove("")) list.add(0, "");
        return list;
    }

    public void setString(int resourceId, String qualifiers, String value) throws IOException {
        PackageBlock pkg = packageBlock();
        ResourceEntry re = pkg.getResource(resourceId);
        if (re == null) throw new IOException("String resource not found.");
        Entry target = null;
        for (Entry e : re) {
            if (qualifiersOf(e).equals(qualifiers)) {
                target = e;
                break;
            }
        }
        if (target == null) throw new IOException("This string has no " + describeQualifiers(qualifiers) + " version.");
        target.setDataAsString(value);
        modified = true;
    }

    private static String qualifiersOf(Entry e) {
        if (e.getResConfig() == null) return "";
        String q = e.getResConfig().getQualifiers();
        return q == null ? "" : q;
    }

    /** "" becomes "Default", "-hi" becomes "hi", "-es-rMX" becomes "es-MX". */
    public static String describeQualifiers(String q) {
        if (q == null || q.isEmpty()) return "Default";
        String s = q.startsWith("-") ? q.substring(1) : q;
        return s.replace("-r", "-");
    }

    // ------------------------------------------------------------------ build

    /** Writes all changes to a new, unsigned APK. */
    public void build(File output) throws IOException {
        if (module.hasTableBlock()) module.refreshTable();
        module.refreshManifest();
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
        if (output.exists() && !output.delete()) throw new IOException("Could not replace " + output);
        module.writeApk(output);
    }

    @Override
    public void close() throws IOException {
        module.close();
    }

    // ------------------------------------------------------------------ helpers

    private PackageBlock packageBlockOrNull() {
        if (!module.hasTableBlock()) return null;
        String pkgName = getPackageName();
        for (PackageBlock p : module.getTableBlock().listPackages()) {
            if (pkgName != null && pkgName.equals(p.getName())) return p;
        }
        return module.getTableBlock().pickOne();
    }

    private PackageBlock packageBlock() throws IOException {
        PackageBlock p = packageBlockOrNull();
        if (p == null) throw new IOException("This APK has no resources.");
        return p;
    }

    private static String cleanPath(String path) throws IOException {
        String p = path.trim().replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty() || p.endsWith("/") || p.contains("../") || p.equals("..")) {
            throw new IOException("\"" + path + "\" is not a valid file path.");
        }
        return p;
    }

    private static String friendlyXmlError(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return "The XML has a mistake and was not saved: " + msg;
    }
}
