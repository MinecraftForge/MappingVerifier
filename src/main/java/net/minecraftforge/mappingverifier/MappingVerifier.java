/*
 * Mapping Verifier
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package net.minecraftforge.mappingverifier;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import net.minecraftforge.srgutils.IMappingFile;

public class MappingVerifier {
    @SuppressWarnings("serial")
    private static Map<String, Function<MappingVerifier, IVerifier>> VERIFIERS = new HashMap<String, Function<MappingVerifier, IVerifier>>() {{
        put("accesslevels", AccessLevels::new);
        put("overridenames", OverrideNames::new);
        put("uniqueids", UniqueIDs::new);
        put("ctrs", Constructors::new);
        put("unamed_classes", UnnamedClasses::new);
    }};
    @SuppressWarnings("serial")
    private static Map<String, Function<MappingVerifier, IVerifier>> EXTRA = new HashMap<String, Function<MappingVerifier, IVerifier>>() {{
        put("class_names", ClassNameStandards::new);
    }};


    private IMappingFile map = null;
    private InheratanceMap inh = new InheratanceMap();
    private Map<String, List<Integer>> ctrs;
    private List<IVerifier> tasks = new ArrayList<>();
    private Map<String, String> suffixes;

    public void addDefaultTasks() {
        VERIFIERS.values().forEach(v -> tasks.add(v.apply(this)));
    }

    public void addTask(String name) {
        Function<MappingVerifier, IVerifier> sup = VERIFIERS.get(name.toLowerCase(Locale.ENGLISH));
        if (sup == null) {
            sup = EXTRA.get(name.toLowerCase(Locale.ENGLISH));
            if (sup == null)
                throw new IllegalArgumentException("Unknown task \"" + name + "\" Known: " + Stream.concat(VERIFIERS.keySet().stream(), EXTRA.keySet().stream()).collect(Collectors.joining(", ")));
        }
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

    public Map<String, List<Integer>> getCtrs() {
        return ctrs;
    }

    public Map<String, String> getSuffixes() {
        return suffixes;
    }

    public void loadMap(File mapFile) throws IOException {
        this.map = IMappingFile.load(mapFile);
    }

    public void loadMap(InputStream mapStream) throws IOException {
        this.map = IMappingFile.load(mapStream);
    }

    public void setMap(IMappingFile map)
    {
        this.map = map;
    }

    public void loadJar(File input) throws IOException {
        try (ZipFile zip = new ZipFile(input)) {
            zip.stream().filter(e -> !e.isDirectory() && e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/")) //Classes Only, No support for multi-release jars.
            .forEach(e -> {
                try {
                    Main.LOG.info("Loading: " + e.getName());
                    inh.processClass(zip.getInputStream(e));
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            });
        }
    }

    public void loadCtrs(File input) throws IOException {
        try (Stream<String> stream = Files.lines(Paths.get(input.toURI()))) {
            List<String[]> lines = stream.map(l -> l.split("#")[0].replaceAll("\\s+$", "")).filter(l -> !l.isEmpty()).map(l -> l.split(" ")).collect(Collectors.toList());
            if (!lines.isEmpty()) {
                this.ctrs = new HashMap<>();
                for (String[] line : lines) {
                    if (line.length != 3)
                        Main.LOG.warning("Invalid CTR Line: " + Arrays.asList(line).stream().collect(Collectors.joining(" ")));
                    else
                        ctrs.computeIfAbsent(line[1] + line[2], k -> new ArrayList<>()).add(Integer.parseInt(line[0]));
                }
            } else {
                Main.LOG.warning("Invalid ctr file: No entries");
            }

        } catch (IOException e) {
            throw new IOException("Could not open ctr file: " + e.getMessage());
        }
    }


    public void loadSfxs(File input) throws IOException {
        try (Stream<String> stream = Files.lines(Paths.get(input.toURI()))) {
            List<String[]> lines = stream.map(l -> l.split("#")[0].replaceAll("\\s+$", "")).filter(l -> !l.isEmpty()).map(l -> l.split(" ")).collect(Collectors.toList());
            if (lines.isEmpty()) {
                Main.LOG.warning("Invalid suffix file: No entries");
            } else {
                this.suffixes = new LinkedHashMap<>();
                for (String[] line : lines) {
                    if (line.length != 2)
                        Main.LOG.warning("Invalid Suffix Line: " + Arrays.asList(line).stream().collect(Collectors.joining(" ")));
                    else
                        this.suffixes.put(line[0], line[1]);
                }
            }
        } catch (IOException e) {
            throw new IOException("Could not open suffix file: " + e.getMessage());
        }
    }
}
