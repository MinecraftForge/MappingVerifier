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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import org.objectweb.asm.Opcodes;

import net.minecraftforge.mappingverifier.InheratanceMap.Access;
import net.minecraftforge.mappingverifier.InheratanceMap.Class;
import net.minecraftforge.mappingverifier.InheratanceMap.Node;
import net.minecraftforge.mappingverifier.Mappings.ClsInfo;


public class MappingVerifierImpl
{
    private Mappings map = new Mappings();
    private InheratanceMap inh = new InheratanceMap();

    public static boolean process(File jarFile, File mapFile) throws IOException
    {
        MappingVerifierImpl mv = new MappingVerifierImpl();
        mv.loadMap(mapFile);
        mv.gatherInheratance(jarFile); //TODO: Add full classpath so we can check all classes including JVM?
        return mv.verify();
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
                    //TSRG/CSRG support?
                    MappingVerifier.LOG.warning("Invalid first mapping line: " + first);
                }
            }
            else
            {
                MappingVerifier.LOG.warning("Invalid map file: No entries");
            }

        }
        catch (IOException e)
        {
            throw new IOException("Could not open map file: " + e.getMessage());
        }
    }


    private void gatherInheratance(File input) throws IOException
    {
        try (ZipFile zip = new ZipFile(input))
        {
            zip.stream().filter(e -> !e.isDirectory() && e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/")) //Classes Only, No support for multi-release jars.
            .forEach(e ->
            {
                try
                {
                    MappingVerifier.LOG.info("Loading: " + e.getName());
                    inh.processClass(zip.getInputStream(e));
                }
                catch (IOException e1)
                {
                    e1.printStackTrace();
                }
            });
        }
    }

    private boolean verify()
    {
        return inh.getRead()
        .sorted((o1, o2) -> o1.name.compareTo(o2.name))
        .reduce(true, (v, cls) ->
        {
            boolean failed = false;
            MappingVerifier.LOG.info("Processing: " + map.map(cls.name));
            ClsInfo info = map.getClass(cls.name);
            failed = cls.fields.values().stream().sequential().sorted((o1, o2) -> o1.name.compareTo(o2.name)).map(entry ->
            {
                String newName = info.map(entry.name);

                Deque<Class> q = new ArrayDeque<>(cls.getStack());
                while (!q.isEmpty())
                {
                    Class parent = q.poll();
                    ClsInfo pinfo = map.getClass(parent.name);

                    Node f = parent.fields.get(pinfo.unmap(newName));
                    if (f != null && !Access.isPrivate(f.access))
                    {
                        /*
                        log("FD: %s/%s %s/%s #Shadow %s/%s",
                            e.name, entry.name,
                            map.map(e.name), newName,
                            map.map(parent.name), newName
                        );
                        */
                        log("Shade: %s/%s -- %s", cls.name, entry.name, newName);
                        log("       %s/%s -- %s", map.map(parent.name), f.name, newName);
                        return false;
                    }
                    parent = parent.getParent();
                }
                return true;
            }).reduce(true, (a, b) -> a && b);


            failed |= cls.methods.values().stream().sequential().sorted((o1, o2) -> o1.name.equals(o2.name) ? o1.desc.compareTo(o2.desc) : o1.name.compareTo(o2.name)).map(mt ->
            {
                if (mt.name.startsWith("<") || ((mt.access & Opcodes.ACC_STATIC) != 0))
                    return true;

                String newName = map.getClass(cls.name).map(mt.name, mt.desc);
                String newSignature = map.mapDesc(mt.desc);

                Deque<Class> q = new ArrayDeque<>(cls.getStack());
                while (!q.isEmpty())
                {
                    Class parent = q.poll();
                    ClsInfo pinfo = map.getClass(parent.name);
                    String unmapped = pinfo.unmap(newName, newSignature);

                    Node m = parent.methods.get(unmapped + mt.desc);

                    if (m != null && !Access.isPrivate(m.access))
                    {
                        if (!mt.name.equals(unmapped))
                        {
                            /*
                            log("MD: %s/%s %s %s/%s %s #Shadow %s/%s",
                                e.name, node.name, node.desc,
                                map.map(e.name), newName, newSignature,
                                map.map(parent.name), newName
                                );
                            */
                            log("Shade: %s/%s %s -- %s", cls.name, mt.name, mt.desc, newName);
                            log("       %s/%s %s -- %s", parent.name, unmapped, mt.desc, newName);
                            return false;
                        }
                    }

                    m = parent.methods.get(mt.name + mt.desc);
                    if (m != null && !Access.isPrivate(m.access))
                    {
                        String mapped = pinfo.map(mt.name, mt.desc);
                        if (!newName.equals(mapped))
                        {
                            /*
                            log("MD: %s/%s %s %s/%s %s #Override %s/%s ",
                                cls.name, mt.name, mt.desc,
                                map.map(cls.name), newName, newSignature,
                                map.map(parent.name), mapped
                            );
                            */
                            log("Override: %s/%s %s -- %s -> %s", cls.name, mt.name, mt.desc, newName, mapped);
                            return false;
                        }
                    }
                }

                return true;
            }).reduce(true, (a, b) -> a && b);

            return failed;
        }, (a,b) -> a && b).booleanValue();
    }

    private void log(String format, String... args)
    {
        MappingVerifier.LOG.warning(String.format(format, (Object[])args));
    }
}
