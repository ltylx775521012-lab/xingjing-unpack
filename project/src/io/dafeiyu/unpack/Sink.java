package io.dafeiyu.unpack;

import java.io.IOException;
import java.io.OutputStream;

/** 解压输出目标抽象：普通目录 or SAF 目录树 */
public interface Sink {

    /** 创建（或覆盖）一个文件，返回可写流 */
    OutputStream open(String relPath) throws IOException;

    /** 创建目录 */
    void mkdir(String relPath) throws IOException;

    /** 给用户看的落盘位置描述 */
    String describe();

    void close() throws IOException;
}
