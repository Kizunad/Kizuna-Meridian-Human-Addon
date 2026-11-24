package dev.kizuna.meridian.human.fabric;

import dev.kizuna.meridian.persistence.DurableStateFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** 跨平台提交回归：不支持目录通道时仍须完整写入，文件同步不能跳过。 */
public final class DurableStateFileTest {
    @Rule
    public final TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void unsupportedDirectorySyncStillSavesAndReplacesFile() throws IOException {
        // Windows 的能力判断必须跳过目录通道，连续原子替换仍保留完整内容。
        var capability = DurableStateFile.directorySync("Windows 11");
        assertEquals(DurableStateFile.DirectorySync.UNSUPPORTED, capability);
        Path file = directory.getRoot().toPath().resolve("state.dat");
        DurableStateFile.write(file, output -> output.write("首次保存".getBytes(StandardCharsets.UTF_8)), capability);
        DurableStateFile.write(file, output -> output.write("再次保存".getBytes(StandardCharsets.UTF_8)), capability);
        assertEquals("再次保存", Files.readString(file));
        assertFalse(Files.exists(file.resolveSibling("state.dat.tmp")));
    }

    @Test
    public void unsupportedDirectorySyncDoesNotSkipFileForce() throws IOException {
        // 写入器返回后制造不可同步的文件，目录能力不能把文件 force 失败误报成功。
        Path file = directory.getRoot().toPath().resolve("state.dat");
        Files.writeString(file, "旧状态");
        try {
            DurableStateFile.write(file, output -> Files.createDirectory(file.resolveSibling("state.dat.tmp")),
                    DurableStateFile.DirectorySync.UNSUPPORTED);
            fail("文件同步失败却完成保存");
        } catch (IOException expected) {
            assertEquals("旧状态", Files.readString(file));
        }
    }
}
