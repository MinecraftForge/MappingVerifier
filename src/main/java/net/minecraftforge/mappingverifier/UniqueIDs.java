/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mappingverifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.minecraftforge.mappingverifier.InheratanceMap.Class;
import net.minecraftforge.mappingverifier.InheratanceMap.Field;
import net.minecraftforge.mappingverifier.InheratanceMap.Method;
import net.minecraftforge.mappingverifier.InheratanceMap.Node;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.srgutils.IMappingFile.IClass;

public class UniqueIDs extends SimpleVerifier {
    protected UniqueIDs(MappingVerifier verifier) {
        super(verifier);
    }

    @Override
    public boolean process() {
        Main.LOG.info("UniqueIDs:");
        InheratanceMap inh = verifier.getInheratance();
        IMappingFile map = verifier.getMappings();
        Map<Integer, Set<String>> claimed = new TreeMap<>();
        Map<String, Set<List<String>>> signatures = new HashMap<>();

        Consumer<String[]> gather = entry -> {
            String idstr = entry[0].split("_")[1];
            if (idstr.matches("\\d+")) {
                claimed.computeIfAbsent(Integer.parseInt(idstr), k -> new TreeSet<>()).add(entry[0]);
                signatures.computeIfAbsent(entry[0], k -> new HashSet<>()).add(Arrays.asList(Arrays.copyOfRange(entry, 1, entry.length)));
            }
        };

        for (Class cls : inh.getOwned()) {
            IClass info = map.getClass(cls.name);

            for (Field field : cls.getFields().values()) {
                String mapped = mapField(info, field.name);
                if (mapped.startsWith("field_") || mapped.startsWith("f_"))
                    gather.accept(new String[] { mapped, cls.name, field.name });
            }

            for (Method method : cls.getMethods().values()) {
                String mapped = mapMethod(info, method.name, method.desc);
                if (mapped.startsWith("func_") || mapped.startsWith("m_"))
                    gather.accept(new String[] { mapped, cls.name, method.name, method.desc });
            }
        }


        boolean success = true;
        for (Entry<Integer, Set<String>> entry : claimed.entrySet()) {
            String name1 = entry.getValue().iterator().next();
            if (entry.getValue().size() == 1 && !different(name1, signatures.get(name1), inh))
                continue;

            error("Duplicate ID: %s (%s)", entry.getKey().toString(), entry.getValue().stream().sorted().collect(Collectors.joining(", ")));

            for (String name : entry.getValue()) {
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
            }
            success = false;
        }
        return success;
    }

    private void merge(Set<String> methods, InheratanceMap inh) {
        Map<String, String> chain = new HashMap<>();
        Set<String> roots = new HashSet<>();
        for (Class cls : inh.getOwned()) {
            for (Method mtd : cls.getMethods().values()) {
                if (mtd.getRoots().size() == 1)
                    continue;

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
            }
        }
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
