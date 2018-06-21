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

import static org.objectweb.asm.ClassReader.SKIP_CODE;
import static org.objectweb.asm.ClassReader.SKIP_DEBUG;
import static org.objectweb.asm.ClassReader.SKIP_FRAMES;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import static org.objectweb.asm.Opcodes.*;


public class InheratanceMap
{
    private Map<String, Class> classes = new HashMap<String, Class>();

    public void processClass(InputStream data) throws IOException
    {
        ClassNode node = new ClassNode();
        ClassReader reader = new ClassReader(data);
        reader.accept(node, SKIP_CODE | SKIP_DEBUG | SKIP_FRAMES);

        Class cls = getClass(node.name);
        cls.parent = getClass(node.superName);
        cls.wasRead = true;

        for (String intf : node.interfaces)
            cls.interfaces.add(getClass(intf));

        for (FieldNode n : node.fields)
            cls.fields.put(n.name + n.desc, new Node(cls, n.name, n.desc, n.access));

        for (MethodNode n : node.methods)
            cls.methods.put(n.name + n.desc, new Node(cls, n.name, n.desc, n.access));
    }

    public Class getClass(String name)
    {
        return classes.computeIfAbsent(name, k -> new Class(name));
    }

    public Stream<Class> getRead()
    {
        return classes.values().stream().filter(e -> e.wasRead);
    }

    public static class Class
    {
        private boolean wasRead = false;
        private Class parent;
        public final String name;
        public final Map<String, Node> fields = new HashMap<String, Node>();
        public final Map<String, Node> methods = new HashMap<String, Node>();
        public final List<Class> interfaces = new ArrayList<>();
        private List<Class> stack = null;

        public Class(String name)
        {
            this.name = name;
        }

        public boolean wasRead()
        {
            return wasRead;
        }

        public Class getParent()
        {
            return parent;
        }

        @Override
        public String toString()
        {
            return this.name + " [" + fields.size() + ", " + methods.size() + "]";
        }

        public List<Class> getStack()
        {
            if (stack == null)
            {
                Set<String> visited = new HashSet<>();

                stack = new ArrayList<>();

                Queue<Class> q = new ArrayDeque<>();
                if (parent != null)
                    q.add(parent);
                this.interfaces.forEach(q::add);

                while (!q.isEmpty())
                {
                    Class cls = q.poll();
                    if (!visited.contains(cls.name))
                    {
                        stack.add(cls);
                        visited.add(cls.name);
                        if (cls.parent != null && !visited.contains(cls.parent.name))
                            q.add(cls.parent);

                        this.interfaces.stream().filter(i -> !visited.contains(i.name)).forEach(q::add);
                    }
                }
            }
            return stack;
        }
    }

    public static class Node
    {
        public final Class owner;
        public final String name;
        public final String desc;
        public final int access;
        private final int hash;

        Node(Class owner, String name, String desc, int access)
        {
            this.owner = owner;
            this.name = name;
            this.desc = desc;
            this.access = access;
            this.hash = (name + desc).hashCode();
        }

        @Override
        public int hashCode()
        {
            return hash;
        }
    }
    public static enum Access
    {
        PRIVATE, DEFAULT, PROTECTED, PUBLIC;
        public static Access get(int acc)
        {
            if ((acc & ACC_PRIVATE)   == ACC_PRIVATE  ) return PRIVATE;
            if ((acc & ACC_PROTECTED) == ACC_PROTECTED) return PROTECTED;
            if ((acc & ACC_PUBLIC)    == ACC_PUBLIC   ) return PUBLIC;
            return DEFAULT;
        }

        public static boolean isPrivate(int acc)
        {
            return get(acc) == PRIVATE;
        }
    }
}
