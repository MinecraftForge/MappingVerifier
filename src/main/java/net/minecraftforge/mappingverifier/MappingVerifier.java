/*
 * Mapping Verifier
 * Copyright (c) 2016-2018.
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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import net.minecraftforge.mappingverifier.Mappings.ClsInfo;

public class MappingVerifier
{
    @SuppressWarnings("serial")
    private static Map<String, Supplier<IVerifier>> VERIFIERS = new HashMap<String, Supplier<IVerifier>>()
    {{
        put("accesslevels", AccessLevels::new);
        put("overridenames", OverrideNames::new);
        put("duplicatesrgids", DuplicateSrgIds::new);
    }};

    private Mappings map = new Mappings();
    private InheratanceMap inh = new InheratanceMap();
    private List<IVerifier> tasks = new ArrayList<>();

    public void addDefaultTasks()
    {
        VERIFIERS.values().forEach(v -> tasks.add(v.get()));
    }

    public void addTask(String name)
    {
        Supplier<IVerifier> sup = VERIFIERS.get(name.toLowerCase(Locale.ENGLISH));
        if (sup == null)
            throw new IllegalArgumentException("Unknown task \"" + name + "\" Known: " + VERIFIERS.keySet().stream().collect(Collectors.joining(", ")));
        tasks.add(sup.get());
    }

    public void addTask(IVerifier task)
    {
        tasks.add(task);
    }

    public boolean verify()
    {
        boolean valid = true;
        for (IVerifier v : tasks)
        {
            valid &= v.process(inh, map);
        }
        return valid;
    }

    public List<IVerifier> getTasks()
    {
        return tasks;
    }

    public void loadMap(File mapFile) throws IOException
    {
        try (Stream<String> stream = Files.lines(Paths.get(mapFile.toURI())))
        {
            BiFunction<String, Character, String[]> rsplit = (i, c) -> {
                int idx = i.lastIndexOf(c);
                return idx == -1 ? new String[]{i} :  new String[]{i.substring(0, idx), i.substring(idx + 1)};
            };

            List<String> lines = stream.map(l -> l.split("#")[0].replaceAll("\\s+$", "")).filter(l -> !l.isEmpty()).collect(Collectors.toList());
            if (!lines.isEmpty())
            {
                String first = lines.get(0);
                if (first.contains("PK:") || first.contains("CL:") || first.contains("FD:") || first.contains("MD:"))
                {
                    lines.stream().map(l -> l.split(" ")).forEachOrdered( l ->
                    {
                        if (l[0].equals("PK:"))
                            map.addPackage(l[1], l[2]);
                        else if (l[0].equals("CL:"))
                            map.addMapping(l[1], l[2]);
                        else if (l[0].equals("FD:"))
                        {
                            String[] ptsO = rsplit.apply(l[1], '/');
                            String[] ptsM = rsplit.apply(l[2], '/');
                            map.getClass(ptsO[0]).putField(ptsO[1], ptsM[1]);
                        }
                        else if (l[0].equals("MD:"))
                        {
                            String[] ptsO = rsplit.apply(l[1], '/');
                            String[] ptsM = rsplit.apply(l[3], '/');
                            map.getClass(ptsO[0]).putMethod(ptsO[1], l[2], ptsM[1], l[4]);
                        }
                    });
                }
                else
                {
                    lines.stream().filter(l -> !l.startsWith("\t")).map(l -> l.split(" ")).filter(l -> l.length == 2).forEach(l -> map.addMapping(l[0], l[1]));
                    ClsInfo currentCls = null;
                    for (String line : lines)
                    {
                        if (line.startsWith("\t"))
                        {
                            if (currentCls == null)
                            {
                                Main.LOG.warning("Invalid TSRG Line, Missing Current class: " + line);
                            }
                            else
                            {
                                String[] parts = line.substring(1).split(" ");
                                if (parts.length == 2)
                                    currentCls.putField(parts[0], parts[1]);
                                else if (parts.length == 3)
                                    currentCls.putMethod(parts[0], parts[1], parts[2], map.mapDesc(parts[1]));
                                else
                                    Main.LOG.warning("Invalid TSRG Line, To many peices: " + line);
                            }
                        }
                        else
                        {
                            String[] parts = line.split(" ");
                            if (parts.length == 2)
                                currentCls = map.getClass(parts[0]);
                            else if (parts.length == 3)
                                map.getClass(parts[0]).putField(parts[1], parts[2]);
                            else if (parts.length == 4)
                                map.getClass(parts[0]).putMethod(parts[1], parts[2], parts[3], map.mapDesc(parts[2]));
                            else
                                Main.LOG.warning("Invalid CSRG Line, To many peices: " + line);
                        }
                    }
                }
            }
            else
            {
                Main.LOG.warning("Invalid map file: No entries");
            }

        }
        catch (IOException e)
        {
            throw new IOException("Could not open map file: " + e.getMessage());
        }
    }

    public void loadJar(File input) throws IOException
    {
        try (ZipFile zip = new ZipFile(input))
        {
            zip.stream().filter(e -> !e.isDirectory() && e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/")) //Classes Only, No support for multi-release jars.
            .forEach(e ->
            {
                try
                {
                    Main.LOG.info("Loading: " + e.getName());
                    inh.processClass(zip.getInputStream(e));
                }
                catch (IOException e1)
                {
                    e1.printStackTrace();
                }
            });
        }
    }
}
