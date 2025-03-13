/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mappingverifier;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.minecraftforge.srgutils.IMappingFile;

public class MappingVerifier {
    @SuppressWarnings("serial")
    private static Map<String, Function<MappingVerifier, IVerifier>> VERIFIERS = new HashMap<String, Function<MappingVerifier, IVerifier>>() {{
        put("accesslevels", AccessLevels::new);
        put("overridenames", OverrideNames::new);
        put("uniqueids", UniqueIDs::new);
    }};

    private IMappingFile map = null;
    private InheratanceMap inh = new InheratanceMap();
    private List<IVerifier> tasks = new ArrayList<>();

    public void addDefaultTasks() {
        VERIFIERS.values().forEach(v -> tasks.add(v.apply(this)));
    }

    public void addTask(String name) {
        Function<MappingVerifier, IVerifier> sup = VERIFIERS.get(name.toLowerCase(Locale.ENGLISH));
        if (sup == null)
            throw new IllegalArgumentException("Unknown task \"" + name + "\" Known: " + VERIFIERS.keySet().stream().collect(Collectors.joining(", ")));
        tasks.add(sup.apply(this));
    }

    public void addTask(IVerifier task) {
        tasks.add(task);
    }

    public boolean verify() {
        inh.resolve();
        boolean valid = true;
        for (IVerifier v : tasks)
            valid &= v.process();
        return valid;
    }

    public List<IVerifier> getTasks() {
        return tasks;
    }

    public IMappingFile getMappings() {
        return map;
    }

    public InheratanceMap getInheratance() {
        return inh;
    }

    public void loadMap(File mapFile) throws IOException {
        this.map = IMappingFile.load(mapFile);
    }

    public void loadMap(InputStream mapStream) throws IOException {
        this.map = IMappingFile.load(mapStream);
    }

    public void setMap(IMappingFile map) {
        this.map = map;
    }

    public void loadLibrary(File input) throws IOException {
        loadJar(input, false);
    }

    public void loadJar(File input) throws IOException {
        loadJar(input, true);
    }

    private void loadJar(File input, boolean owned) throws IOException {
        try (ZipFile zip = new ZipFile(input)) {
            Enumeration<? extends ZipEntry> itr = zip.entries();
            while (itr.hasMoreElements()) {
                ZipEntry e = itr.nextElement();
                if (e.isDirectory() ||
                    !e.getName().endsWith(".class") || // Classes Only
                    e.getName().startsWith("META-INF/") // No Multi-Release support
                ) {
                    continue;
                }

                try {
                    Main.LOG.info("Loading: " + e.getName());
                    inh.processClass(zip.getInputStream(e), owned);
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
        }
    }
}
