package com.sangam.apkforge.engine;

import com.reandroid.apk.ApkBundle;
import com.reandroid.apk.ApkModule;
import com.reandroid.app.AndroidManifest;
import com.reandroid.archive.ByteInputSource;
import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.value.DataType;
import com.reandroid.arsc.value.Entry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Edits APK files directly (no decompiling to folders) using ARSCLib.
 * Written in Java so it can also be tested on a normal computer.
 *
 * The output of every method here is UNSIGNED. Sign it afterwards (ApkSigning).
 */
public final class ApkEditEngine {

    private ApkEditEngine() {}

    /** What to change. Any field left null is left as it is in the APK. */
    public static final class Edits {
        public String appName;
        public String packageName;
        public String versionName;
        public Integer versionCode;
        /** PNG bytes for a new launcher icon (any size, 192 x 192 is ideal). */
        public byte[] iconPng;
    }

    /** Values currently in the APK, used to pre-fill the edit screen. */
    public static final class CurrentValues {
        public String appName;
        public String packageName;
        public String versionName;
        public Integer versionCode;
    }

    private static final String ICON_NAME = "apkforge_custom_icon";
    private static final String ICON_PATH = "res/" + ICON_NAME + ".png";

    // ------------------------------------------------------------------ read

    public static CurrentValues readCurrent(File apk) throws IOException {
        ApkModule module = ApkModule.loadApkFile(apk);
        try {
            AndroidManifestBlock manifest = module.getAndroidManifest();
            if (manifest == null) throw new IOException("This APK has no AndroidManifest.xml.");
            CurrentValues v = new CurrentValues();
            v.packageName = manifest.getPackageName();
            v.versionName = manifest.getVersionName();
            v.versionCode = manifest.getVersionCode();
            v.appName = resolveLabel(module, manifest);
            return v;
        } finally {
            module.close();
        }
    }

    private static String resolveLabel(ApkModule module, AndroidManifestBlock manifest) {
        String literal = manifest.getApplicationLabelString();
        if (literal != null && !literal.isEmpty()) return literal;
        Integer ref = manifest.getApplicationLabelReference();
        if (ref == null || ref == 0 || !module.hasTableBlock()) return null;
        try {
            List<Entry> entries = module.getTableBlock().resolveReference(ref);
            // Prefer the default (no language) value.
            for (Entry e : entries) {
                if (e.getResConfig() != null && e.getResConfig().isDefault() && e.getResValue() != null) {
                    String s = e.getResValue().getDataAsString();
                    if (s != null) return s;
                }
            }
            for (Entry e : entries) {
                if (e.getResValue() != null && e.getResValue().getDataAsString() != null) {
                    return e.getResValue().getDataAsString();
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    // ------------------------------------------------------------------ edit

    /** Applies [edits] to [input] and writes an unsigned APK to [output]. */
    public static void applyEdits(File input, File output, Edits edits) throws IOException {
        ApkModule module = ApkModule.loadApkFile(input);
        try {
            AndroidManifestBlock manifest = module.getAndroidManifest();
            if (manifest == null) throw new IOException("This APK has no AndroidManifest.xml.");

            if (notBlank(edits.packageName) && !edits.packageName.equals(manifest.getPackageName())) {
                checkPackageName(edits.packageName);
                renamePackage(module, manifest, edits.packageName.trim());
            }
            if (notBlank(edits.versionName)) manifest.setVersionName(edits.versionName.trim());
            if (edits.versionCode != null) manifest.setVersionCode(edits.versionCode);
            if (notBlank(edits.appName)) setAppName(manifest, edits.appName.trim());
            if (edits.iconPng != null && edits.iconPng.length > 0) setIcon(module, manifest, edits.iconPng);

            manifest.refresh();
            if (module.hasTableBlock()) module.refreshTable();
            module.refreshManifest();
            write(module, output);
        } finally {
            module.close();
        }
    }

    private static void setAppName(AndroidManifestBlock manifest, String name) {
        manifest.setApplicationLabel(name);
        // Launcher activities often have their own label, which the home screen shows instead.
        for (ResXmlElement activity : launcherActivities(manifest)) {
            ResXmlAttribute label = activity.searchAttributeByResourceId(AndroidManifest.ID_label);
            if (label != null) label.setDataAsString(name);
        }
    }

    private static void setIcon(ApkModule module, AndroidManifestBlock manifest, byte[] png) throws IOException {
        if (!module.hasTableBlock()) throw new IOException("This APK has no resources, so its icon can't be changed.");
        PackageBlock pkg = module.getTableBlock().pickOne();
        if (pkg == null) throw new IOException("Could not find the APK's resource package.");

        // Add our own drawable instead of overwriting the original icon files,
        // which other parts of the app might also use.
        Entry entry = pkg.getOrCreate("", "drawable", ICON_NAME);
        entry.setDataAsString(ICON_PATH);
        module.removeInputSource(ICON_PATH);
        module.add(new ByteInputSource(png, ICON_PATH));

        int id = entry.getResourceId();
        manifest.setIconResourceId(id);
        if (manifest.getRoundIconResourceId() != 0) manifest.setRoundIconResourceId(id);

        // Activities and aliases can declare their own icon too.
        for (ResXmlElement el : allElements(manifest.getManifestElement())) {
            if (!isComponent(el)) continue;
            ResXmlAttribute icon = el.searchAttributeByResourceId(AndroidManifest.ID_icon);
            if (icon != null) icon.setDataAsResourceId(id);
            ResXmlAttribute round = el.searchAttributeByResourceId(AndroidManifest.ID_roundIcon);
            if (round != null) round.setDataAsResourceId(id);
        }
    }

    /**
     * Changes the package name so the edited app installs next to the original (a "clone").
     * Besides the package itself, anything that must be unique per app is renamed too:
     * content provider authorities and custom permissions.
     */
    private static void renamePackage(ApkModule module, AndroidManifestBlock manifest, String newPkg) {
        String oldPkg = manifest.getPackageName();
        ResXmlElement root = manifest.getManifestElement();

        for (ResXmlElement el : allElements(root)) {
            String tag = el.getName();

            // 1. Class names written as ".Foo" or "Foo" are relative to the OLD package.
            //    Make them absolute first, or they would point to classes that don't exist.
            if (isComponent(el) || AndroidManifest.TAG_application.equals(tag)) {
                absolutize(el, AndroidManifest.ID_name, oldPkg);
                absolutize(el, AndroidManifest.ID_targetActivity, oldPkg);
            }

            // 2. Provider authorities must be unique on the phone.
            if (AndroidManifest.TAG_provider.equals(tag)) {
                ResXmlAttribute auth = el.searchAttributeByResourceId(AndroidManifest.ID_authorities);
                String value = stringOf(auth);
                if (value != null) {
                    String[] parts = value.split(";");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < parts.length; i++) {
                        if (i > 0) sb.append(';');
                        String p = parts[i];
                        sb.append(p.contains(oldPkg) ? p.replace(oldPkg, newPkg) : newPkg + "." + p);
                    }
                    auth.setDataAsString(sb.toString());
                }
            }

            // 3. Custom permissions named after the package must be unique too,
            //    or Android refuses to install the clone next to the original.
            if (AndroidManifest.TAG_permission.equals(tag)
                    || AndroidManifest.TAG_uses_permission.equals(tag)
                    || "uses-permission-sdk-23".equals(tag)
                    || "permission-tree".equals(tag)
                    || "permission-group".equals(tag)) {
                replacePrefix(el.searchAttributeByResourceId(AndroidManifest.ID_name), oldPkg, newPkg);
            }
            if (isComponent(el)) {
                for (Iterator<ResXmlAttribute> it = el.getAttributes(); it.hasNext(); ) {
                    ResXmlAttribute a = it.next();
                    String n = a.getName();
                    if ("permission".equals(n) || "readPermission".equals(n) || "writePermission".equals(n)) {
                        replacePrefix(a, oldPkg, newPkg);
                    }
                }
            }
        }

        manifest.setPackageName(newPkg);
        if (module.hasTableBlock()) {
            for (PackageBlock pkg : module.getTableBlock().listPackages()) {
                if (oldPkg.equals(pkg.getName())) pkg.setName(newPkg);
            }
        }
    }

    private static void absolutize(ResXmlElement el, int attrId, String oldPkg) {
        ResXmlAttribute a = el.searchAttributeByResourceId(attrId);
        String v = stringOf(a);
        if (v == null || v.isEmpty()) return;
        if (v.startsWith(".")) a.setDataAsString(oldPkg + v);
        else if (!v.contains(".")) a.setDataAsString(oldPkg + "." + v);
    }

    private static void replacePrefix(ResXmlAttribute a, String oldPkg, String newPkg) {
        String v = stringOf(a);
        if (v != null && v.startsWith(oldPkg + ".")) a.setDataAsString(newPkg + v.substring(oldPkg.length()));
    }

    // ------------------------------------------------------------------ split APKs

    /**
     * Merges a base APK and its split APKs into one installable APK (unsigned).
     * [apks] must include the base APK.
     */
    public static void mergeSplits(List<File> apks, File workDir, File output) throws IOException {
        File dir = new File(workDir, "merge_in");
        deleteRecursively(dir);
        if (!dir.mkdirs()) throw new IOException("Could not create " + dir);
        int i = 0;
        for (File f : apks) {
            String name = f.getName().endsWith(".apk") ? f.getName() : f.getName() + ".apk";
            copy(f, new File(dir, (i++) + "_" + name));
        }
        mergeDirectory(dir, output);
        deleteRecursively(dir);
    }

    /**
     * Extracts every .apk inside a bundle file (.apks, .xapk, .apkm) and merges them.
     * Returns the number of APKs that were merged.
     */
    public static int mergeBundleFile(File bundle, File workDir, File output) throws IOException {
        File dir = new File(workDir, "bundle_in");
        deleteRecursively(dir);
        if (!dir.mkdirs()) throw new IOException("Could not create " + dir);
        int count = 0;
        try (ZipFile zip = new ZipFile(bundle)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || !e.getName().toLowerCase().endsWith(".apk")) continue;
                String flat = e.getName().replace('/', '_').replace('\\', '_');
                File target = new File(dir, count + "_" + flat);
                try (InputStream in = zip.getInputStream(e); OutputStream out = new FileOutputStream(target)) {
                    pipe(in, out);
                }
                count++;
            }
        } catch (java.util.zip.ZipException ze) {
            throw new IOException("This file is not a valid bundle. It may be damaged or encrypted.");
        }
        if (count == 0) throw new IOException("No APK files were found inside this bundle.");
        if (count == 1) {
            File only = dir.listFiles()[0];
            copy(only, output);
        } else {
            mergeDirectory(dir, output);
        }
        deleteRecursively(dir);
        return count;
    }

    private static void mergeDirectory(File dir, File output) throws IOException {
        ApkBundle bundle = new ApkBundle();
        try {
            bundle.loadApkDirectory(dir, false);
            if (bundle.countModules() == 0) throw new IOException("No APK files to merge.");
            ApkModule merged = bundle.mergeModules(false);
            try {
                removeSplitMarkers(merged);
                if (merged.hasTableBlock()) merged.refreshTable();
                merged.refreshManifest();
                write(merged, output);
            } finally {
                merged.close();
            }
        } finally {
            bundle.close();
        }
    }

    /** After merging, the manifest must stop saying "I need split APKs", or Android refuses it. */
    private static void removeSplitMarkers(ApkModule module) {
        if (!module.hasAndroidManifest()) return;
        AndroidManifestBlock manifest = module.getAndroidManifest();
        ResXmlElement root = manifest.getManifestElement();
        if (root == null) return;

        root.removeAttributesWithId(AndroidManifest.ID_isSplitRequired);
        root.removeAttributesWithId(AndroidManifest.ID_requiredSplitTypes);
        root.removeAttributesWithName("requiredSplitTypes");
        root.removeAttributesWithName("splitTypes");
        root.removeAttributesWithName("isSplitRequired");

        ResXmlElement app = manifest.getApplicationElement();
        if (app != null) {
            app.removeAttributesWithId(AndroidManifest.ID_isSplitRequired);
            List<ResXmlElement> toRemove = new ArrayList<>();
            for (ResXmlElement child : children(app)) {
                if (!AndroidManifest.TAG_meta_data.equals(child.getName())) continue;
                String name = stringOf(child.searchAttributeByResourceId(AndroidManifest.ID_name));
                if (name == null) continue;
                if (name.startsWith("com.android.vending.splits")
                        || name.equals("com.android.vending.derived.apk.id")
                        || name.startsWith("com.android.stamp.")) {
                    toRemove.add(child);
                }
            }
            for (ResXmlElement el : toRemove) app.remove(el);
        }
        manifest.refresh();
    }

    // ------------------------------------------------------------------ helpers

    private static void write(ApkModule module, File output) throws IOException {
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        if (output.exists() && !output.delete()) throw new IOException("Could not replace " + output);
        module.writeApk(output);
    }

    static void checkPackageName(String name) throws IOException {
        String n = name.trim();
        if (!n.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
            throw new IOException("\"" + n + "\" is not a valid package name. Use letters, numbers and dots, "
                    + "with at least one dot, like com.example.myapp");
        }
    }

    private static boolean isComponent(ResXmlElement el) {
        String t = el.getName();
        return AndroidManifest.TAG_activity.equals(t)
                || AndroidManifest.TAG_activity_alias.equals(t)
                || AndroidManifest.TAG_service.equals(t)
                || AndroidManifest.TAG_receiver.equals(t)
                || AndroidManifest.TAG_provider.equals(t);
    }

    private static List<ResXmlElement> launcherActivities(AndroidManifestBlock manifest) {
        List<ResXmlElement> result = new ArrayList<>();
        ResXmlElement app = manifest.getApplicationElement();
        if (app == null) return result;
        for (ResXmlElement el : children(app)) {
            String t = el.getName();
            if (!AndroidManifest.TAG_activity.equals(t) && !AndroidManifest.TAG_activity_alias.equals(t)) continue;
            boolean launcher = false;
            for (ResXmlElement f : children(el)) {
                if (!AndroidManifest.TAG_intent_filter.equals(f.getName())) continue;
                for (ResXmlElement c : children(f)) {
                    if (AndroidManifest.TAG_category.equals(c.getName())
                            && "android.intent.category.LAUNCHER".equals(
                                    stringOf(c.searchAttributeByResourceId(AndroidManifest.ID_name)))) {
                        launcher = true;
                    }
                }
            }
            if (launcher) result.add(el);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<ResXmlElement> children(ResXmlElement el) {
        List<ResXmlElement> list = new ArrayList<>();
        if (el == null) return list;
        Iterator<?> it = el.getElements();
        while (it.hasNext()) {
            Object o = it.next();
            if (o instanceof ResXmlElement) list.add((ResXmlElement) o);
        }
        return list;
    }

    private static List<ResXmlElement> allElements(ResXmlElement root) {
        List<ResXmlElement> out = new ArrayList<>();
        if (root == null) return out;
        List<ResXmlElement> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            ResXmlElement el = stack.remove(stack.size() - 1);
            out.add(el);
            stack.addAll(children(el));
        }
        return out;
    }

    private static String stringOf(ResXmlAttribute a) {
        if (a == null || a.getDataType() != DataType.STRING) return null;
        return a.getDataAsString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new java.io.FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            pipe(in, out);
        }
    }

    private static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
