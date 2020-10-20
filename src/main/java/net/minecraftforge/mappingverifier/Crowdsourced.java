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

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.siegmar.fastcsv.reader.CsvContainer;
import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRow;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.srgutils.IRenamer;
import net.minecraftforge.srgutils.IMappingFile.IField;
import net.minecraftforge.srgutils.IMappingFile.IMethod;

public class Crowdsourced
{
    private static URLConnection getConnection(String address) throws IOException
    {
        int MAX = 3;
        URL url = new URL(address);
        URLConnection connection = null;
        for (int x = 0; x < MAX; x++)
        {
            connection = url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            if (connection instanceof HttpURLConnection)
            {
                HttpURLConnection hcon = (HttpURLConnection)connection;
                hcon.setInstanceFollowRedirects(false);
                int res = hcon.getResponseCode();
                if (res == HttpURLConnection.HTTP_MOVED_PERM || res == HttpURLConnection.HTTP_MOVED_TEMP)
                {
                    String location = hcon.getHeaderField("Location");
                    hcon.disconnect(); //Kill old connection.
                    if (x == MAX-1)
                    {
                        System.out.println("Invalid number of redirects: " + location);
                        return null;
                    }
                    else
                    {
                        System.out.println("Following redirect: " + location);
                        url = new URL(url, location); // Nested in case of relative urls.
                    }
                }
                else
                    break;
            }
            else
                break;
        }
        return connection;
    }


    private static boolean downloadFile(File target, String url) throws IOException
    {
        URLConnection connection = getConnection(url);
        if (connection != null)
        {
            if (!target.getParentFile().exists())
                target.getParentFile().mkdirs();
            Files.copy(connection.getInputStream(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        }
        return false;
    }

    public static void process(Logger log, MappingVerifier mv, String snapVersion, File mapFile) throws IOException
    {
        File target = new File("data/snapshot/" + snapVersion + ".zip");
        if (!target.exists())
        {
            String url = "https://files.minecraftforge.net/maven/de/oceanlabs/mcp/mcp_snapshot/" + snapVersion + "/mcp_snapshot-" + snapVersion + ".zip";
            log.info("Downloading: " + url);
            if (!downloadFile(target, url))
                throw new IOException("Download Failed");
        }

        log.info("Loading: " + target.getPath());
        Map<String, String> names = new HashMap<>();
        try (ZipFile zip = new ZipFile(target)) {
            List<ZipEntry> entries = zip.stream().filter(e -> e.getName().endsWith(".csv")).collect(Collectors.toList());
            for (ZipEntry entry : entries) {
                CsvReader reader = new CsvReader();
                reader.setContainsHeader(true);
                CsvContainer csv = reader.read(new InputStreamReader(zip.getInputStream(entry)));
                for (CsvRow row : csv.getRows()) {
                    String searge = row.getField("searge");
                    if (searge == null)
                        searge = row.getField("param");
                    names.put(searge, row.getField("name"));
                }
            }
        }

        IMappingFile srg = IMappingFile.load(mapFile);
        IMappingFile mapped = srg.rename(new IRenamer()
        {
            public String rename(IField value)
            {
                return names.getOrDefault(value.getMapped(), value.getMapped());
            }

            public String rename(IMethod value)
            {
                return names.getOrDefault(value.getMapped(), value.getMapped());
            }
        });
        mv.setMap(mapped);
    }
}