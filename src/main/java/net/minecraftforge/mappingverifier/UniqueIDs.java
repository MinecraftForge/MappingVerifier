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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.minecraftforge.mappingverifier.InheratanceMap.Node;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.srgutils.IMappingFile.IClass;

public class UniqueIDs extends SimpleVerifier {
    protected UniqueIDs(MappingVerifier verifier) {
        super(verifier);
    }

    @Override
    public boolean process() {
        InheratanceMap inh = verifier.getInheratance();
        IMappingFile map = verifier.getMappings();
        Map<Integer, Set<String>> claimed = new HashMap<>();
        Map<String, Set<List<String>>> signatures = new HashMap<>();
        Consumer<String[]> gather = entry -> {
            String idstr = entry[0].split("_")[1];
            if (idstr.matches("\\d+")) {
                claimed.computeIfAbsent(Integer.parseInt(idstr), k -> new HashSet<>()).add(entry[0]);
                signatures.computeIfAbsent(entry[0], k -> new HashSet<>()).add(Arrays.asList(Arrays.copyOfRange(entry, 1, entry.length)));
            }
        };

        inh.getRead().forEach(cls -> {
            IClass info = map.getClass(cls.name);

            cls.fields.values().stream()
            .map(field -> new String[]{info.remapField(field.name), cls.name, field.name})
            .filter(entry -> entry[0].startsWith("field_") || entry[0].startsWith("f_"))
            .forEach(gather);

            cls.methods.values().stream()
            .map(method  -> new String[] {info.remapMethod(method.name, method.desc), cls.name, method.name, method.desc})
            .filter(entry -> entry[0].startsWith("func_") || entry[0].startsWith("m_"))
            .forEach(gather);
        });

        return claimed.entrySet().stream()
        .filter(e -> e.getValue().size() > 1 || different(e.getValue().iterator().next(), signatures.get(e.getValue().iterator().next()), inh))
        .sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey())).map(entry -> {
            error("Duplicate ID: %s (%s)", entry.getKey().toString(), entry.getValue().stream().sorted().collect(Collectors.joining(", ")));
            entry.getValue().stream().sorted().forEach(name -> {
                Set<List<String>> sigs = signatures.get(name);
                List<String> fields = new ArrayList<>();
                Map<String, Integer> methods = new HashMap<>();
                if (sigs != null) {
                    sigs.stream().forEach(pts -> {
                        if (pts.size() == 2)
                            fields.add(pts.get(0) + '/' + pts.get(1));
                        else if (pts.size() == 1) { // Constructor
                            int idx = pts.get(0).indexOf('(');
                            String cls = pts.get(0).substring(0, idx);
                            String desc = pts.get(0).substring(idx);
                            String key = cls + "/<init>" + desc;
                            methods.put(key, methods.computeIfAbsent(key, k -> 0) + 1);
                        } else {
                            InheratanceMap.Class cls = inh.getClass(pts.get(0));
                            InheratanceMap.Method mtd = cls.getMethod(pts.get(1), pts.get(2));
                            mtd.getRoots().forEach(root -> methods.put(root.getKey(), methods.computeIfAbsent(root.getKey(), k -> 0) + 1));
                        }
                    });
                }
                error("    %s (%s)", name, Stream.concat(fields.stream(), methods.entrySet().stream().map(e -> e.getKey() + '[' + e.getValue() + ']')).sorted().collect(Collectors.joining(", ")));
            });
            return false;
        }).reduce(true, (a,b)-> a && b);
    }

    private void merge(Set<String> methods, InheratanceMap inh) {
        Map<String, String> chain = new HashMap<>();
        Set<String> roots = new HashSet<>();
        inh.getRead().forEach(cls -> {
            cls.methods.values().stream().filter(mtd -> mtd.getRoots().size() > 1).forEach(mtd -> {
                List<String> overrides = mtd.getRoots().stream().map(Node::getKey).distinct().collect(Collectors.toList());
                String root = null;
                for (String o : overrides) {
                    if (roots.contains(o)) {
                        overrides.remove(o);
                        root = o;
                        break;
                    }
                }
                if (root == null)
                    root = overrides.remove(0);
                roots.add(root);
                for (String o : overrides)
                    chain.put(o, root);
            });
        });
        Set<String> ret = new HashSet<>();
        for (String mtd : methods) {
            while (chain.containsKey(mtd))
                mtd = chain.get(mtd);
            ret.add(mtd);
        }
        methods.clear();
        methods.addAll(ret);
    }

    private boolean different(String id, Set<List<String>> entries, InheratanceMap inh) {
        if (entries == null || entries.stream().map(e -> e.stream().skip(1).collect(Collectors.joining(" "))).distinct().count() == 1)
            return false;

        if (entries.stream().mapToInt(List::size).distinct().count() != 1) //Has both method and field
            return true;

        if (entries.iterator().next().size() == 2) // Only Fields
            return true; //More then one field? Thats bad

        Set<String> roots = new HashSet<>();
        // Only Methods
        for (List<String> pts : entries) {
            InheratanceMap.Class cls = inh.getClass(pts.get(0));
            InheratanceMap.Method mtd = cls.getMethod(pts.get(1), pts.get(2));
            mtd.getRoots().forEach(root -> roots.add(root.getKey()));
        }

        if (roots.size() > 1)
            merge(roots, inh);

        return roots.size() > 1;
    }
}
