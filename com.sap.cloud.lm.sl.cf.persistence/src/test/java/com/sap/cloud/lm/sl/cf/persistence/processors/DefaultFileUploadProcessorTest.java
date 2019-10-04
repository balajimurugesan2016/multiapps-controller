package com.sap.cloud.lm.sl.cf.persistence.processors;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.FileOutputStream;
import java.io.IOException;

import org.junit.Test;

public class DefaultFileUploadProcessorTest {

    @Test
    public void testNoAdditionalProcessingIsDone() throws IOException {
        final byte[] data = "test".getBytes();
        DefaultFileUploadProcessor classUnderTest = new DefaultFileUploadProcessor();

        FileOutputStream os = mock(FileOutputStream.class);
        classUnderTest.writeFileChunk(os, data, data.length);
        verify(os, times(1)).write(data, 0, data.length);
    }

}
