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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
}
