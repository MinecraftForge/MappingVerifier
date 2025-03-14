/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mappingverifier;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import net.minecraftforge.mappingverifier.InheratanceMap.Class;
import net.minecraftforge.mappingverifier.InheratanceMap.Field;
import net.minecraftforge.mappingverifier.InheratanceMap.Method;
import net.minecraftforge.mappingverifier.InheratanceMap.Method.Bounce;
import net.minecraftforge.mappingverifier.InheratanceMap.Node;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.srgutils.IMappingFile.IClass;

public class OverrideNames extends SimpleVerifier {
    protected OverrideNames(MappingVerifier verifier) {
        super(verifier);
    }

    @Override
    public boolean process() {
        Main.LOG.info("Override Names:");
        InheratanceMap inh = verifier.getInheratance();
        IMappingFile map = verifier.getMappings();
        IMappingFile reverse = map.reverse();
        boolean ret = true;
        ret  = checkNormal(inh, map, reverse);
        ret &= checkInterfaces(inh);
        //ret &= checkDeep(inh);
        ret &= checkPerLevel(inh);
        return ret;
    }

    // This one we check every method defined in a class, walking its parent tree.
    // Catches simple subclasses who define the override.
    private boolean checkNormal(InheratanceMap inh, IMappingFile map, IMappingFile reverse) {
        boolean success = true;
        for (Class cls : inh.getOwned()) {
            Main.LOG.finest("  Processing: " + map.remapClass(cls.name));
            IClass info = map.getClass(cls.name);
            for (Field entry : cls.getFields().values()) {
                if (Modifier.isStatic(entry.access))
                    continue;

                String newName = info.remapField(entry.name);

                for (Class parent : cls.getStack()) {
                    IClass pinfo = reverse.getClass(map.remapClass(parent.name));
                    Node f = parent.getField(pinfo == null ? newName : pinfo.remapField(newName));
                    if (f != null && !Modifier.isPrivate(f.access)) {
                        error("  Shade: %s/%s %s/%s %s", cls.name, entry.name, pinfo.getOriginal(), f.name, newName);
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
                        if (Modifier.isPrivate(m.access)) {
                            if (newName.startsWith("func_") || newName.startsWith("m_")) { //Private with the same name are valid. but if we're in SRG names, we should make it unique to allow separate names to be crowdsourced.
                                error("  BadOverride: %s/%s %s -> %s/%s %s -- %s", cls.name, mt.name, mt.desc, parent.name, unmapped, mt.desc, newName);
                                success = false;
                                continue;
                            }
                        } else if (!mt.name.equals(unmapped)) { //Obf name is different, so it's not a proper override, but SRG name matches, so bad shade.
                            error("  Shade: %s/%s %s/%s %s %s", cls.name, mt.name, parent.name, unmapped, mt.desc, newName);
                            success = false;
                            continue;
                        }
                    }

                    m = parent.getMethod(mt.name, mt.desc);
                    if (m != null && !Modifier.isPrivate(m.access)) { //Parent has same obfed name as child and parent isn't private, make sure they have the same mapped name to maintain the override.
                        String mapped = pinfo == null ? mt.name : pinfo.remapMethod(mt.name, mt.desc);
                        if (!newName.equals(mapped)) {
                            error("  Override: %s/%s %s -- %s -> %s", cls.name, mt.name, mt.desc, newName, mapped);
                            success = false;
                            continue;
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
    private boolean checkInterfaces(InheratanceMap inh) {
        boolean success = true;

        Map<Class, Set<Class>> interfaces = new TreeMap<>();
        for (Class cls : inh.getOwned()) {
            if (!cls.isInterface())
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
            Main.LOG.finest("  Processing Interface: " + mapClass(cls.name));
            Set<Class> stack = new TreeSet<>(e.getValue());

            for (Class child : e.getValue()) {
                for (Class parent : child.getStack())
                    stack.addAll(parent.getStack());
            }

            for (Method mt : cls.getMethods().values()) {
                if (Modifier.isStatic(mt.access) || mt.name.startsWith("<"))
                    continue;

                String newName = mapMethod(cls.name, mt.name, mt.desc);
                for (Class parent : stack) {
                    Node m = parent.getMethod(mt.name, mt.desc);

                    if (m != null && !Modifier.isPrivate(m.access)) {
                        String mapped = mapMethod(parent.name, mt.name, mt.desc);
                        if (!newName.equals(mapped)) {
                            error("  Override: %s/%s %s -- %s -> %s", parent.name, mt.name, mt.desc, mapped, newName);
                            success = false;
                            continue;
                        }
                    }
                }
            }
        }

        return success;
    }

    /**
     * Resolves all overrides for each class, this works fine except it outputs the same errors multiple times.
     * Need to fix that later.
     */
    private boolean checkPerLevel(InheratanceMap inh) {
        boolean success = true;
        Map<Class, LinkInfo> links = buildLinks();
        Map<Class, ExposedMethods> cache = new HashMap<>();
        Queue<Class> q = new UniqueDeque<>(inh.getOwned());

        while(!q.isEmpty()) {
            Class cls = q.poll();
            LinkInfo info = links.get(cls);
            if (info == null)
                throw new IllegalStateException("Did not find link info for " + cls.name);

            ExposedMethods self = resolveLevel(inh, links, cache, cls);

            // Get a copy so we don't modify the back end
            Map<String, Set<Method>> methods = new HashMap<>();
            for (Entry<String, Set<Method>> entry : self.methods.entrySet())
                methods.put(entry.getKey(), new HashSet<>(entry.getValue()));

            // Merge bounces together
            for (Entry<String, Set<String>> entry : self.bounces.entrySet()) {
                Set<Method> left = methods.get(entry.getKey());
                for (String target : entry.getValue()) {
                    Set<Method> right = methods.get(target);

                    if (left == right) {
                        // Already merged
                    } else if (left == null || right == null) {
                        Main.LOG.warning("Unable to merge bouncer: " + cls.getName() + ' ' + mapClass(cls.getName()));
                        Main.LOG.warning("  " + entry.getKey() + ": " + left);
                        Main.LOG.warning("  " + target + ": " + right);
                    } else {
                        left.addAll(right);
                        methods.put(target, left);
                    }
                }
            }

            success &= verifyOverrides(methods.values());
        }

        return success;
    }

    /**
     * Resolves all the public methods for this class. This is a joined copy of the parents state, allowing us to modify it without modifying the parent.
     */
    private static ExposedMethods resolveLevel(InheratanceMap inh, Map<Class, LinkInfo> links, Map<Class, ExposedMethods> cache, Class cls) {
        ExposedMethods ret = cache.get(cls);
        if (ret != null)
            return ret;

        ret = new ExposedMethods(cls);
        List<ExposedMethods> parents = new ArrayList<>();
        if (cls.getParent() != null)
            parents.add(resolveLevel(inh, links, cache, cls.getParent()));
        for (Class intf : cls.getInterfaces())
            parents.add(resolveLevel(inh, links, cache, intf));

        for (ExposedMethods parent : parents) {
            for (Entry<String, Set<Method>> e : parent.methods.entrySet())
                ret.methods.computeIfAbsent(e.getKey(), k -> new HashSet<>()).addAll(e.getValue());
            for (Entry<String, Set<String>> e: parent.bounces.entrySet())
                ret.bounces.computeIfAbsent(e.getKey(), k -> new HashSet<>()).addAll(e.getValue());
        }

        for (Method method : cls.getMethods().values()) {
            // We don't care about initializers
            if ("<init>".equals(method.name) || "<cinit>".equals(method.name))
                continue;

            if (Modifier.isStatic(method.getAccess()) || Modifier.isPrivate(method.getAccess())) {
                Set<Method> override = ret.methods.get(method.getSimple());
                if (override != null)
                    Main.LOG.warning("  Invalid Override: " + cls.name + " reduces " + method.getSimple() + " from " + override);
                ret.privates.add(method);
            } else {
                ret.methods.computeIfAbsent(method.getSimple(), k -> new HashSet<>()).add(method);

                if (method.isBouncer()) {
                    LinkInfo sinfo = links.get(method.owner);
                    Bounce bounce = method.getBounceTarget();
                    Method target = sinfo.self.getMethod(bounce.name, bounce.desc);
                    if (!method.owner.name.equals(bounce.owner)) {
                        // For some reason, if you implement a generic interface, your subclasses get a synthetic bouncer method as well as the class you implement it on.
                        // This should only ever be the case where the method is in the stack.. So we shouldn't need anything special.
                        // Lets throw a warning if it is this odd edge case.
                        Class owner = inh.getClass(bounce.owner);
                        LinkInfo link = links.get(owner);
                        if (link == null)
                            Main.LOG.warning("Could not find link info for: " + bounce.owner);
                        else {
                            if (link.getStack().stream().map(Class::getName).noneMatch(bounce.owner::equals))
                                Main.LOG.warning("Out of stack bouncer: " + method + " -> " + bounce);
                            target = link.self.getMethod(bounce.name, bounce.desc);
                        }
                    }

                    if (target == null)
                        Main.LOG.warning("Invalid bouncer, can't find target: " + bounce);
                    else
                        ret.bounces.computeIfAbsent(method.getSimple(), k -> new HashSet<>()).add(target.getSimple());
                }
            }
        }

        cache.put(cls, ret);
        return ret;
    }

    private static class ExposedMethods {
        private final Map<String, Set<Method>> methods = new HashMap<>();
        private final Map<String, Set<String>> bounces = new HashMap<>();
        private final Set<Method> privates = new HashSet<>();
        @SuppressWarnings("unused")
        private final Class self;

        private ExposedMethods(Class self) {
            this.self = self;
        }
    }

    // This doesn't really work, it catches all my cases, but gets more. I don't want to delete right now.
    @SuppressWarnings("unused")
    private boolean checkDeep(InheratanceMap inh) {
        boolean success = true;
        Map<Class, LinkInfo> links = buildLinks();
        Map<Method, Set<Method>> methods = new HashMap<>();
        Map<Method, Method> bounces = new HashMap<>();

        Queue<Class> q = new UniqueDeque<>(inh.getOwned());
        while (!q.isEmpty()) {
            Class cls = q.poll();
            LinkInfo info = links.get(cls);
            if (info == null)
                throw new IllegalStateException("Did not find link info for " + cls.name);

            for (Method method : cls.getMethods().values()) {
                if (methods.containsKey(method))
                    continue;

                // TODO: [MappingVerifier][Overrides] Private methods shadowing inherited methods, it's a compile error reducing visibility.
                if (!method.isInheritable())
                    continue;

                Set<Method> linked = new HashSet<>();
                Queue<Class> stack = new UniqueDeque<>(info.getStack());
                stack.addAll(info.getInterfaces());

                while (!stack.isEmpty()) {
                    Class sibling = stack.poll();

                    Method mtd = sibling.getMethod(method.name, method.desc);
                    if (mtd == null || !mtd.isInheritable())
                        continue;

                    if (!linked.add(mtd))
                        continue;

                    if (mtd.isBouncer()) {
                        LinkInfo sinfo = links.get(mtd.owner);
                        Bounce bounce = mtd.getBounceTarget();
                        Method target = sinfo.self.getMethod(bounce.name, bounce.desc);
                        if (!mtd.owner.name.equals(bounce.owner)) {
                            // For some reason, if you implement a generic interface, your subclasses get a synthetic bouncer method as well as the class you implement it on.
                            // This should only ever be the case where the method is in the stack.. So we shouldn't need anything special.
                            // Lets throw a warning if it is this odd edge case.
                            Class owner = inh.getClass(bounce.owner);
                            LinkInfo link = links.get(owner);
                            if (link == null)
                                Main.LOG.warning("Could not find link info for: " + sibling.getName());
                            else {
                                if (link.getStack().stream().map(Class::getName).noneMatch(bounce.owner::equals))
                                    Main.LOG.warning("Out of stack bouncer: " + mtd + " -> " + bounce);
                                target = link.self.getMethod(bounce.name, bounce.desc);
                            }
                        }

                        if (target == null)
                            Main.LOG.warning("Invalid bouncer, can't find target: " + bounce);
                        else {
                            bounces.put(mtd, target);
                            // Make sure that the target is processed so we can merge things later
                            if (!methods.containsKey(target))
                                q.add(target.owner);
                        }
                    }

                    // TODO: [MappingVerifier][Overrides] Interfaces which have bouncers could cause us to link multiple children, with different descriptors, not sure how to articulate it
                    if (sibling.isInterface()) {
                        LinkInfo link = links.get(sibling);
                        if (link == null) {
                            Main.LOG.warning("Could not find link info for: " + sibling.getName());
                            continue;
                        }

                        for (LinkInfo child : link.children) {
                            stack.addAll(child.getStack());
                            stack.addAll(child.getInterfaces());
                        }
                    }
                }

                if (linked.stream().anyMatch(methods::containsKey)) {
                    Set<Method> _new = new HashSet<>(linked);

                    for (Method mtd : linked) {
                        Set<Method> s = methods.get(mtd);
                        if (s != null)
                            _new.addAll(s);
                    }

                    for (Method mtd : _new)
                        methods.put(mtd, _new);
                } else {
                    for (Method mtd : linked)
                        methods.put(mtd, linked);
                }
            }
        }

        // Merge bounced methods together
        for (Entry<Method, Method> entry : bounces.entrySet()) {
            Set<Method> left = methods.get(entry.getKey());
            Set<Method> right = methods.get(entry.getValue());

            if (left == right) {
                // Already merged
            } else if (left == null || right == null) {
                Main.LOG.warning("Unable to merge bouncer:");
                Main.LOG.warning("  " + entry.getKey() + ": " + left);
                Main.LOG.warning("  " + entry.getValue() + ": " + right);
            } else {
                for (Method mtd : right) {
                    if (left.add(mtd))
                        methods.put(mtd, left);
                }
            }
        }

        success &= verifyOverrides(methods.values());
        return success;
    }

    private boolean verifyOverrides(Collection<Set<Method>> links) {
        boolean success = true;
        Set<Set<Method>> visited = new HashSet<>();

        for (Set<Method> linked : links) {
            if (!visited.add(linked))
                continue;

            Map<String, Set<Method>> named = new HashMap<>();
            for (Method mtd : linked) {
                String mapped = mapMethod(mtd.owner.name, mtd.name, mtd.desc);
                named.computeIfAbsent(mapped, k -> new HashSet<>()).add(mtd);
            }

            if (named.size() != 1) {
                error("Invalid Override: ");
                for (Entry<String, Set<Method>> e : named.entrySet()) {
                    error("  " + e.getKey() + ": " + e.getValue().stream().map(Method::getKey).sorted().collect(Collectors.joining(", ")));
                }
                success = false;
            }
        }

        return success;
    }

    private static class LinkInfo implements Comparable<LinkInfo> {
        private final Class self;
        private final Set<LinkInfo> children = new TreeSet<>();
        private final Set<LinkInfo> parents = new HashSet<>();
        private Set<Class> stack;
        private Set<Class> interfaces;

        public LinkInfo(Class self) {
            this.self = self;
        }

        @Override
        public int compareTo(LinkInfo o) {
            if (o == null) return -1;
            int ret = this.children.size() - o.children.size();
            if (ret != 0) return ret;
            return this.self.name.compareTo(o.self.name);
        }

        @Override
        public String toString() {
            return this.self.name + " [Children: " + this.children.size() + ']';
        }

        private static final Set<String> ROOTS = new HashSet<>(Arrays.asList(
            "java/lang/Object",
            "java/lang/Record",
            "java/lang/Enum"
        ));

        // Get all classes linked to this class by direct inheritance only.
        public Set<Class> getStack() {
            if (stack == null) {
                Set<Class> ret = new LinkedHashSet<>();

                Queue<LinkInfo> q = new UniqueDeque<>(e -> e.self);
                q.add(this);

                while (!q.isEmpty()) {
                    LinkInfo info = q.poll();

                    if (!ret.add(info.self))
                        continue;

                    for (LinkInfo parent : info.parents) {
                        if (!parent.self.isInterface())
                            q.add(parent);
                    }

                    if (ROOTS.contains(info.self.name))
                        continue;

                    for (LinkInfo child : info.children) {
                        if (!child.self.isInterface())
                            q.add(child);
                    }
                }

                this.stack = ret;
            }
            return stack;
        }

        // Gather every interface that is attached to this class by anything in its known stack
        public Set<Class> getInterfaces() {
            if (this.interfaces == null) {
                Set<Class> ret = new HashSet<>();

                Queue<Class> q = new UniqueDeque<>();
                for (Class cls : getStack()) {
                    q.addAll(cls.getInterfaces());
                    while (!q.isEmpty()) {
                        Class intf = q.poll();
                        ret.add(intf);
                        if (intf.getParent() != null && intf.getParent().isInterface())
                            q.add(intf.getParent());
                        q.addAll(intf.getInterfaces());
                    }
                }

                this.interfaces = ret;
            }
            return this.interfaces;
        }
    }

    private Map<Class, LinkInfo> buildLinks() {
        InheratanceMap inh = verifier.getInheratance();
        Map<Class, LinkInfo> info = new HashMap<>();
        Queue<Class> q = new UniqueDeque<>(inh.getOwned());

        while (!q.isEmpty()) {
            Class cls = q.poll();
            LinkInfo self = info.computeIfAbsent(cls, LinkInfo::new);

            Class parent = cls.getParent();
            if (parent != null) {
                LinkInfo parentInfo = info.computeIfAbsent(parent, LinkInfo::new);
                parentInfo.children.add(self);
                self.parents.add(parentInfo);
                q.add(parent);
            }

            for (Class inf : cls.getInterfaces()) {
                LinkInfo infInfo = info.computeIfAbsent(inf, LinkInfo::new);
                infInfo.children.add(self);
                self.parents.add(infInfo);
                q.add(inf);
            }
        }

        return info;
    }
}
