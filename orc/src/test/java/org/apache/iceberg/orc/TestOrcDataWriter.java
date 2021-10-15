/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.orc;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileContent;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.Files;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.orc.GenericOrcReader;
import org.apache.iceberg.data.orc.GenericOrcWriter;
import org.apache.iceberg.exceptions.RuntimeIOException;
import org.apache.iceberg.hadoop.HadoopOutputFile;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.io.PositionOutputStream;
import org.apache.iceberg.io.SeekableInputStream;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.types.Types;
import org.apache.orc.OrcFile;
import org.apache.orc.StripeInformation;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.jupiter.api.Assertions;
import org.junit.rules.TemporaryFolder;

public class TestOrcDataWriter {
  private static final Schema SCHEMA =
      new Schema(
          Types.NestedField.required(1, "id", Types.LongType.get()),
          Types.NestedField.optional(2, "data", Types.StringType.get()),
          Types.NestedField.optional(3, "binary", Types.BinaryType.get()));

  private List<Record> records;

  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Before
  public void createRecords() {
    GenericRecord record = GenericRecord.create(SCHEMA);

    ImmutableList.Builder<Record> builder = ImmutableList.builder();
    builder.add(record.copy(ImmutableMap.of("id", 1L, "data", "a")));
    builder.add(record.copy(ImmutableMap.of("id", 2L, "data", "b")));
    builder.add(record.copy(ImmutableMap.of("id", 3L, "data", "c")));
    builder.add(record.copy(ImmutableMap.of("id", 4L, "data", "d")));
    builder.add(record.copy(ImmutableMap.of("id", 5L, "data", "e")));

    this.records = builder.build();
  }

  private List<Long> stripeOffsetsFromReader(DataFile dataFile) throws IOException {
    return stripeOffsetsFromReader(dataFile, OrcFile.readerOptions(new Configuration()));
  }

  private List<Long> stripeOffsetsFromReader(DataFile dataFile, OrcFile.ReaderOptions options)
      throws IOException {
    return OrcFile.createReader(new Path(dataFile.path().toString()), options).getStripes().stream()
        .map(StripeInformation::getOffset)
        .collect(Collectors.toList());
  }

  @Test
  public void testDataWriter() throws IOException {
    OutputFile file = Files.localOutput(temp.newFile());

    SortOrder sortOrder = SortOrder.builderFor(SCHEMA).withOrderId(10).asc("id").build();

    DataWriter<Record> dataWriter =
        ORC.writeData(file)
            .schema(SCHEMA)
            .createWriterFunc(GenericOrcWriter::buildWriter)
            .overwrite()
            .withSpec(PartitionSpec.unpartitioned())
            .withSortOrder(sortOrder)
            .build();

    try {
      for (Record record : records) {
        dataWriter.write(record);
      }
    } finally {
      dataWriter.close();
    }

    DataFile dataFile = dataWriter.toDataFile();
    Assert.assertEquals(dataFile.splitOffsets(), stripeOffsetsFromReader(dataFile));
    Assert.assertEquals("Format should be ORC", FileFormat.ORC, dataFile.format());
    Assert.assertEquals("Should be data file", FileContent.DATA, dataFile.content());
    Assert.assertEquals("Record count should match", records.size(), dataFile.recordCount());
    Assert.assertEquals("Partition should be empty", 0, dataFile.partition().size());
    Assert.assertEquals(
        "Sort order should match", sortOrder.orderId(), (int) dataFile.sortOrderId());
    Assert.assertNull("Key metadata should be null", dataFile.keyMetadata());

    List<Record> writtenRecords;
    try (CloseableIterable<Record> reader =
        ORC.read(file.toInputFile())
            .project(SCHEMA)
            .createReaderFunc(fileSchema -> GenericOrcReader.buildReader(SCHEMA, fileSchema))
            .build()) {
      writtenRecords = Lists.newArrayList(reader);
    }

    Assert.assertEquals("Written records should match", records, writtenRecords);
  }

  @Test
  public void testNoSupportForDummyScheme() throws IOException {
    ProxyOutputFile outFile = new ProxyOutputFile(Files.localOutput(temp.newFile()));
    Assertions.assertThrows(
        RuntimeIOException.class,
        () -> HadoopOutputFile.fromPath(new Path(outFile.location()), new Configuration()),
        "No FileSystem for scheme \"dummy\"");
  }

  @Test
  public void testUsingFileIO() throws IOException {
    // Show that FileSystem access is not possible for the file we are supplying as the scheme
    // dummy is not handled
    ProxyOutputFile outFile = new ProxyOutputFile(Files.localOutput(temp.newFile()));
    Assertions.assertThrows(
        RuntimeIOException.class,
        () -> HadoopOutputFile.fromPath(new Path(outFile.location()), new Configuration()),
        "No FileSystem for scheme \"dummy\"");

    // We are creating the proxy
    SortOrder sortOrder = SortOrder.builderFor(SCHEMA).withOrderId(10).asc("id").build();

    DataWriter<Record> dataWriter =
        ORC.writeData(outFile)
            .schema(SCHEMA)
            .createWriterFunc(GenericOrcWriter::buildWriter)
            .overwrite()
            .withSpec(PartitionSpec.unpartitioned())
            .withSortOrder(sortOrder)
            .build();

    try {
      for (Record record : records) {
        dataWriter.write(record);
      }
    } finally {
      dataWriter.close();
    }

    DataFile dataFile = dataWriter.toDataFile();
    OrcFile.ReaderOptions options =
        OrcFile.readerOptions(new Configuration())
            .filesystem(new ORC.InputFileSystem(outFile.toInputFile()))
            .maxLength(outFile.toInputFile().getLength());
    Assert.assertEquals(dataFile.splitOffsets(), stripeOffsetsFromReader(dataFile, options));
    Assert.assertEquals("Format should be ORC", FileFormat.ORC, dataFile.format());
    Assert.assertEquals("Should be data file", FileContent.DATA, dataFile.content());
    Assert.assertEquals("Record count should match", records.size(), dataFile.recordCount());
    Assert.assertEquals("Partition should be empty", 0, dataFile.partition().size());
    Assert.assertEquals(
        "Sort order should match", sortOrder.orderId(), (int) dataFile.sortOrderId());
    Assert.assertNull("Key metadata should be null", dataFile.keyMetadata());

    List<Record> writtenRecords;
    try (CloseableIterable<Record> reader =
        ORC.read(outFile.toInputFile())
            .project(SCHEMA)
            .createReaderFunc(fileSchema -> GenericOrcReader.buildReader(SCHEMA, fileSchema))
            .build()) {
      writtenRecords = Lists.newArrayList(reader);
    }

    Assert.assertEquals("Written records should match", records, writtenRecords);
  }

  private static class ProxyInputFile implements InputFile {
    private final InputFile inputFile;

    private ProxyInputFile(InputFile inputFile) {
      this.inputFile = inputFile;
    }

    @Override
    public long getLength() {
      return inputFile.getLength();
    }

    @Override
    public SeekableInputStream newStream() {
      return inputFile.newStream();
    }

    @Override
    public String location() {
      return "dummy://" + inputFile.location();
    }

    @Override
    public boolean exists() {
      return inputFile.exists();
    }
  }

  private static class ProxyOutputFile implements OutputFile {
    private final OutputFile outputFile;

    private ProxyOutputFile(OutputFile outputFile) {
      this.outputFile = outputFile;
    }

    @Override
    public PositionOutputStream create() {
      return outputFile.create();
    }

    @Override
    public PositionOutputStream createOrOverwrite() {
      return outputFile.createOrOverwrite();
    }

    @Override
    public String location() {
      return "dummy://" + outputFile.location();
    }

    @Override
    public InputFile toInputFile() {
      return new ProxyInputFile(outputFile.toInputFile());
    }
  }
}
