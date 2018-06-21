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
package net.miencraftforge.mappingverifier;

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
import net.miencraftforge.mappingverifier.InheratanceMap.Class;
import net.miencraftforge.mappingverifier.InheratanceMap.Node;
import net.miencraftforge.mappingverifier.Mappings.ClsInfo;
import net.miencraftforge.mappingverifier.InheratanceMap.Access;


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
        return inh.getRead().reduce(true, (v, e) ->
        {
            boolean failed = false;
            MappingVerifier.LOG.info("Processing: " + map.map(e.name));
            ClsInfo info = map.getClass(e.name);
            failed = e.fields.values().stream().map(entry ->
            {
                String newName = info.map(entry.name);

                Deque<Class> q = new ArrayDeque<>(e.getStack());
                while (!q.isEmpty())
                {
                    Class parent = q.poll();
                    ClsInfo pinfo = map.getClass(parent.name);

                    Node f = parent.fields.get(pinfo.unmap(newName));
                    if (f != null && !Access.isPrivate(f.access))
                    {
                        log("FD: %s/%s %s/%s #Shadow %s/%s",
                            e.name, entry.name,
                            map.map(e.name), newName,
                            map.map(parent.name), newName
                        );
                        return false;
                    }
                    parent = parent.getParent();
                }
                return true;
            }).anyMatch(k -> !k);


            failed |= e.methods.values().stream().map(node ->
            {
                if (node.name.startsWith("<") || ((node.access & Opcodes.ACC_STATIC) != 0))
                    return true;

                String newName = map.getClass(e.name).map(node.name, node.desc);

                if (e.name.equals("sr") && newName.equals("func_201681_c"))
                    System.currentTimeMillis();

                String newSignature = map.mapDesc(node.desc);

                Deque<Class> q = new ArrayDeque<>(e.getStack());
                while (!q.isEmpty())
                {
                    Class parent = q.poll();
                    ClsInfo pinfo = map.getClass(parent.name);
                    String unmapped = pinfo.unmap(newName, newSignature);

                    Node m = parent.methods.get(unmapped + node.desc);
                    if (m != null && !Access.isPrivate(m.access))
                    {
                        if (!node.name.equals(unmapped))
                        {
                            log("Invalid Method Shading: %s/%s%s (%s)", map.map(parent.name), newName, newSignature, pinfo.unmap(newName));
                            log("MD: %s/%s %s %s/%s %s #Shadow %s/%s",
                                e.name, node.name, node.desc,
                                map.map(e.name), newName, newSignature,
                                map.map(parent.name), newName
                                );
                            return false;
                        }
                    }

                    m = parent.methods.get(node.name + node.desc);
                    if (m != null && !Access.isPrivate(m.access))
                    {
                        String mapped = pinfo.map(node.name, node.desc);
                        if (!newName.equals(mapped))
                        {
                            log("MD: %s/%s %s %s/%s %s #Override %s/%s ",
                                e.name, node.name, node.desc,
                                map.map(e.name), newName, newSignature,
                                map.map(parent.name), mapped
                            );
                            return false;
                        }
                    }
                }

                return true;
            }).anyMatch(k -> !k);

            return failed;
        }, (a,b) -> a && b).booleanValue();
    }

    private void log(String format, String... args)
    {
        MappingVerifier.LOG.warning(String.format(format, args));
    }
}
