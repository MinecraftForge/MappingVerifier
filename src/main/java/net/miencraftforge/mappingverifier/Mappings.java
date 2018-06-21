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

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import org.objectweb.asm.Type;

public class Mappings
{
    private Map<String, ClsInfo> classes = new HashMap<>();
    private Map<String, String> co_to_m = new HashMap<>();
    private Map<String, String> cm_to_o = new HashMap<>();
    private Map<String, String> po_to_m = new HashMap<>();
    private Map<String, String> pm_to_o = new HashMap<>();

    public ClsInfo getClass(String cls)
    {
        return classes.computeIfAbsent(cls, k -> new ClsInfo(k));
    }

    public void addMapping(String obf, String mapped)
    {
        co_to_m.put(obf, mapped);
        cm_to_o.put(mapped, obf);
    }

    public void addPackage(String obfed, String maped)
    {
        obfed = obfed.equals(".") ? "" : obfed.endsWith("/") ? obfed : obfed + "/";
        maped = maped.equals(".") ? "" : maped.endsWith("/") ? maped : obfed + "/";

        po_to_m.put(obfed, maped);
        pm_to_o.put(maped, obfed);
    }

    public String mapPackage(String pkg)
    {
        return po_to_m.getOrDefault(pkg, pkg);
    }

    public String unmapPackage(String pkg)
    {
        return pm_to_o.getOrDefault(pkg, pkg);
    }

    public String map(String cls)
    {
        int sub = cls.lastIndexOf('$');
        int idx = cls.lastIndexOf('/');

        if (sub != -1)
            return co_to_m.computeIfAbsent(cls, k -> map(cls.substring(0, sub)) + '$' + cls.substring(sub + 1));
        else if (idx != -1)
            return co_to_m.computeIfAbsent(cls, k -> mapPackage(cls.substring(0, idx + 1)) + cls.substring(idx + 1));
        return co_to_m.computeIfAbsent(cls, k -> mapPackage("") + cls);
    }

    public String unmap(String cls)
    {
        int sub = cls.lastIndexOf('$');
        int idx = cls.indexOf('/');

        if (sub != -1)
            return cm_to_o.computeIfAbsent(cls, k -> unmap(cls.substring(0, sub - 1)) + cls.substring(sub + 1));
        else if (idx != -1)
            return cm_to_o.computeIfAbsent(cls, k -> unmapPackage(cls.substring(0, idx)) + cls.substring(idx + 1));
        return cm_to_o.computeIfAbsent(cls, k -> unmapPackage("") + cls);
    }

    public String mapDesc(String desc)
    {
        return mapDesc(desc, k -> map(k));
    }

    public String unmapDesc(String desc)
    {
        return mapDesc(desc, k -> unmap(k));
    }

    private String mapDesc(String desc, Function<String, String> mapper)
    {
        final StringBuilder sb = new StringBuilder();
        Consumer<Type> append = t ->
        {
            if (t.getSort() == Type.ARRAY)
            {
                for (int x = 0; x < t.getDimensions(); x++)
                    sb.append('[');
                t = t.getElementType();
            }

            if (t.getSort() == Type.OBJECT)
                sb.append('L').append(mapper.apply(t.getInternalName())).append(';');
            else
                sb.append(t.getDescriptor());
        };

        sb.append('(');
        for (Type t : Type.getArgumentTypes(desc))
            append.accept(t);
        sb.append(')');
        append.accept(Type.getReturnType(desc));
        return sb.toString();
    }

    public static class ClsInfo
    {
        private final String name;
        private Map<String, String> fo_to_m = new HashMap<>();
        private Map<String, String> fm_to_o = new HashMap<>();
        private Map<String, String> mo_to_m = new HashMap<>();
        private Map<String, String> mm_to_o = new HashMap<>();

        public ClsInfo(String name)
        {
            this.name = name;
        }

        public void putField(String obfed, String maped)
        {
            fo_to_m.put(obfed, maped);
            fm_to_o.put(maped, obfed);
        }
        public void putMethod(String obfed, String obfedSig, String maped, String mapedSig)
        {
            mo_to_m.put(obfed + obfedSig, maped);
            mm_to_o.put(maped + mapedSig, obfed);
        }

        public String map(String field)
        {
            return fo_to_m.getOrDefault(field, field);
        }

        public String unmap(String field)
        {
            return fm_to_o.getOrDefault(field, field);
        }

        public String map(String method, String signature)
        {
            return mo_to_m.getOrDefault(method + signature, method);
        }

        public String unmap(String method, String signature)
        {
            return mm_to_o.getOrDefault(method + signature, method);
        }

        @Override
        public String toString()
        {
            return name + "[" + fo_to_m.size() + ", " + mo_to_m.size() + "]";
        }
    }
}
