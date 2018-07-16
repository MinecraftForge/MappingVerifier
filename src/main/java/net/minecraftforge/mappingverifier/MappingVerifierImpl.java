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
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

public class MappingVerifierImpl
{
    private Mappings map = new Mappings();
    private InheratanceMap inh = new InheratanceMap();

    private List<IVerifier> tasks = Arrays.asList(OverrideNames.INSTANCE, AccessLevels.INSTANCE);

    public static void process(File jarFile, File mapFile) throws IOException
    {
        MappingVerifierImpl mv = new MappingVerifierImpl();
        mv.loadMap(mapFile);
        mv.gatherInheratance(jarFile); //TODO: Add full classpath so we can check all classes including JVM?
        mv.verify();
    }

    private void verify()
    {
        for (IVerifier v : tasks)
        {
            MappingVerifier.LOG.warning("Task: " + v.getName());
            v.process(inh, map);
        }
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
}
