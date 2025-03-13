/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mappingverifier;

import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.objectweb.asm.Opcodes;

import net.minecraftforge.mappingverifier.InheratanceMap.Access;
import net.minecraftforge.mappingverifier.InheratanceMap.Class;
import net.minecraftforge.mappingverifier.InheratanceMap.Field;
import net.minecraftforge.mappingverifier.InheratanceMap.Method;
import net.minecraftforge.mappingverifier.InheratanceMap.Node;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.srgutils.IMappingFile.IClass;

public class OverrideNames extends SimpleVerifier {
    protected OverrideNames(MappingVerifier verifier) {
        super(verifier);
    }

    @Override
    public boolean process() {
        InheratanceMap inh = verifier.getInheratance();
        IMappingFile map = verifier.getMappings();
        IMappingFile reverse = map.reverse();
        return checkNormal(inh, map, reverse) && checkInterfaces(inh, map);
    }

    // This one we check every method defined in a class, walking its parent tree.
    // Catches simple subclasses who define the override.
    private boolean checkNormal(InheratanceMap inh, IMappingFile map, IMappingFile reverse) {
        boolean success = true;
        for (Class cls : inh.getOwned()) {
            Main.LOG.info("  Processing: " + map.remapClass(cls.name));
            IClass info = map.getClass(cls.name);
            for (Field entry : cls.getFields().values()) {
                if ((entry.access & Opcodes.ACC_STATIC) != 0)
                    continue;

                String newName = info.remapField(entry.name);

                for (Class parent : cls.getStack()) {
                    IClass pinfo = reverse.getClass(map.remapClass(parent.name));
                    Node f = parent.getField(pinfo == null ? newName : pinfo.remapField(newName));
                    if (f != null && !Access.isPrivate(f.access)) {
                        error("    Shade: %s/%s %s/%s %s", cls.name, entry.name, pinfo.getOriginal(), f.name, newName);
                        success = false;
                        continue;
                    }
                }
            }

            for (Method mt : cls.getMethods().values()) {
                if (Modifier.isStatic(mt.access) || mt.name.startsWith("<"))
                    continue;

                IClass clsI = map.getClass(cls.name);
                String newName = clsI.remapMethod(mt.name, mt.desc);
                String newSignature = map.remapDescriptor(mt.desc);

                for (Class parent : cls.getStack()) {
                    IClass pinfo = map.getClass(parent.name);
                    IClass rinfo = pinfo == null ? null : reverse.getClass(pinfo.getMapped());
                    String unmapped = rinfo == null ? newName : rinfo.remapMethod(newName, newSignature);
                    Node m = parent.getMethod(unmapped, mt.desc);
                    if (m != null) {//Parent has same mapped name
                        if (Access.isPrivate(m.access)) {
                            if (newName.startsWith("func_") || newName.startsWith("m_")) { //Private with the same name are valid. but if we're in SRG names, we should make it unique to allow separate names to be crowdsourced.
                                error("    BadOverride: %s/%s %s -> %s/%s %s -- %s", cls.name, mt.name, mt.desc, parent.name, unmapped, mt.desc, newName);
                                success = false;
                                continue;
                            }
                        } else if (!mt.name.equals(unmapped)) { //Obf name is different, so it's not a proper override, but SRG name matches, so bad shade.
                            error("    Shade: %s/%s %s/%s %s %s", cls.name, mt.name, parent.name, unmapped, mt.desc, newName);
                            success = false;
                            continue;
                        }
                    }

                    m = parent.getMethod(mt.name, mt.desc);
                    if (m != null && !Access.isPrivate(m.access)) { //Parent has same obfed name as child and parent isn't private, make sure they have the same mapped name to maintain the override.
                        String mapped = pinfo.remapMethod(mt.name, mt.desc);
                        if (!newName.equals(mapped)) {
                            error("    Override: %s/%s %s -- %s -> %s", cls.name, mt.name, mt.desc, newName, mapped);
                            success = false;
                        }
                    }
                }
            }
        }

        return success;
    }

    /*
     * Here we check interfaces specifically, because there are edge cases where a class will implement a
     * interface, and have it's super-classes satisfy that interface's method.
     *
     * Example:
     *
     * class Foo {
     *   int getCount(){ return 0; }
     * }
     * interface ICountable {
     *   int getCount();
     * }
     * class Bar extends Foo implements ICountable {}
     */
    private boolean checkInterfaces(InheratanceMap inh, IMappingFile map) {
        boolean success = true;

        Map<Class, Set<Class>> interfaces = new TreeMap<>();
        for (Class cls : inh.getOwned()) {
            if (!Modifier.isInterface(cls.getAccess()))
                continue;
            interfaces.put(cls, new HashSet<>());
        }

        for (Class cls : inh.getOwned()) {
            for (Class inf : cls.getInterfaces()) {
                if (!interfaces.containsKey(inf))
                    continue;
                interfaces.computeIfAbsent(inf, k -> new HashSet<>()).add(inf);
            }
        }


        for (Entry<Class, Set<Class>> e : interfaces.entrySet()) {
            Class cls = e.getKey();
            Main.LOG.info("  Processing Interface: " + map.remapClass(cls.name));
            Set<Class> stack = new TreeSet<>(e.getValue());

            for (Class child : e.getValue()) {
                for (Class parent : child.getStack())
                    stack.addAll(parent.getStack());
            }

            for (Method mt : cls.getMethods().values()) {
                if (Modifier.isStatic(mt.access) || mt.name.startsWith("<"))
                    continue;

                String newName = map.getClass(cls.name).remapMethod(mt.name, mt.desc);
                for (Class parent : stack) {
                    IClass pinfo = map.getClass(parent.name);

                    Node m = parent.getMethod(mt.name, mt.desc);

                    if (m != null && !Access.isPrivate(m.access)) {
                        String mapped = pinfo.remapMethod(mt.name, mt.desc);
                        if (!newName.equals(mapped)) {
                            error("    Override: %s/%s %s -- %s -> %s", parent.name, mt.name, mt.desc, mapped, newName);
                            success = false;
                            continue;
                        }
                    }
                }
            }
        }

        return success;
    }
}
