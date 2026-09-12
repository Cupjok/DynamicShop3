package me.sat7.dynamicshop.files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class CustomConfigTest
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void brokenFileIsBackedUpAndNeverOverwritten() throws Exception
    {
        File f = tmp.newFile("MyShop.yml");
        String broken = "Options:\n  title: 'unclosed\n0:\n  mat: DIAMOND\n  value: 10\n";
        Files.writeString(f.toPath(), broken, StandardCharsets.UTF_8);

        CustomConfig cc = new CustomConfig();
        cc.setupFile(f);

        assertTrue(cc.IsLoadFailed());
        File[] backups = tmp.getRoot().listFiles((dir, name) -> name.startsWith("MyShop.yml.broken-"));
        assertNotNull(backups);
        assertEquals(1, backups.length);
        assertEquals(broken, Files.readString(backups[0].toPath(), StandardCharsets.UTF_8));

        cc.get().set("Options.title", "overwritten");
        cc.save();
        assertEquals(broken, Files.readString(f.toPath(), StandardCharsets.UTF_8));

        // loading the same broken file again (restart) does not pile up identical copies
        new CustomConfig().setupFile(f);
        assertEquals(1, tmp.getRoot().listFiles((dir, name) -> name.startsWith("MyShop.yml.broken-")).length);

        // fixed by the admin -> reload works and saving is allowed again
        Files.writeString(f.toPath(), "Options:\n  title: ok\n", StandardCharsets.UTF_8);
        cc.reload();
        assertFalse(cc.IsLoadFailed());
        assertEquals("ok", cc.get().getString("Options.title"));
        cc.get().set("Options.title", "saved");
        cc.save();
        assertTrue(Files.readString(f.toPath(), StandardCharsets.UTF_8).contains("saved"));
    }

    @Test
    public void newAndValidFilesSaveAtomically() throws Exception
    {
        File f = new File(tmp.getRoot(), "sub/New.yml");
        CustomConfig cc = new CustomConfig();
        cc.setupFile(f);
        assertFalse(cc.IsLoadFailed());
        assertTrue(f.exists());

        cc.get().set("0.mat", "STONE");
        cc.get().set("0.value", 1.5);
        cc.save();

        CustomConfig again = new CustomConfig();
        again.setupFile(f);
        assertEquals("STONE", again.get().getString("0.mat"));
        assertEquals(1.5, again.get().getDouble("0.value"), 0);
        assertFalse(new File(f.getParentFile(), "New.yml.tmp").exists());
    }
}
