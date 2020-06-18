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

import java.util.ArrayList;
import java.util.List;

public abstract class SimpleVerifier implements IVerifier
{
    protected final MappingVerifier verifier;
    private List<String> errors = new ArrayList<>();

    protected SimpleVerifier(MappingVerifier verifier)
    {
        this.verifier = verifier;
    }

    @Override
    public List<String> getErrors()
    {
        return errors;
    }

    protected void error(String format, String... args)
    {
        String line = String.format(format, (Object[])args);
        Main.LOG.fine(line);
        errors.add(line);
    }
}
