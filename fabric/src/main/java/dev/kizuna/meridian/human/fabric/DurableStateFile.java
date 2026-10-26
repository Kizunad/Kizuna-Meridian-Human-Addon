package dev.kizuna.meridian.human.fabric;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** 在同目录提交文件；Windows 不支持打开目录通道，但仍强制同步文件内容。 */
final class DurableStateFile {
    enum DirectorySync { REQUIRED, UNSUPPORTED }

    @FunctionalInterface
    interface Writer {
        void write(Path temporary) throws IOException;
    }

    private DurableStateFile() {
        // 文件提交没有实例状态，调用方仅在提交成功后更新确认索引。
    }

    static DirectorySync directorySync(String operatingSystem) {
        // Windows 的目录不能用 FileChannel 打开；Unix 平台的真实同步错误不能吞掉。
        if (operatingSystem.startsWith("Windows")) {
            return DirectorySync.UNSUPPORTED;
        }
        return DirectorySync.REQUIRED;
    }

    static void write(Path file, Writer writer, DirectorySync directorySync) throws IOException {
        // 先提交文件内容，再原子替换名称；失败时调用方必须保留待保存和待确认状态。
        Path target = file.toAbsolutePath();
        Path directory = target.getParent();
        Files.createDirectories(directory);
        syncDirectory(directory.getParent(), directorySync);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        writer.write(temporary);
        force(temporary, StandardOpenOption.WRITE);
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        syncDirectory(directory, directorySync);
    }

    private static void syncDirectory(Path directory, DirectorySync directorySync) throws IOException {
        // 仅跳过平台不支持的目录同步，文件 force 和原子替换始终是成功的必要条件。
        if (directorySync == DirectorySync.REQUIRED) {
            force(directory, StandardOpenOption.READ);
        }
    }

    private static void force(Path path, StandardOpenOption option) throws IOException {
        // force 的异常传给保存者，不能在刷盘失败后发出交接确认。
        try (FileChannel channel = FileChannel.open(path, option)) {
            channel.force(true /* metaData */);
        }
    }
}
