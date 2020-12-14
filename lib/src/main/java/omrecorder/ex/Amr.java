package omrecorder.ex;


import android.annotation.TargetApi;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;

import omrecorder.AudioSource;
import omrecorder.CustomAbstractRecorder;
import omrecorder.PullTransport;

public class Amr extends CustomAbstractRecorder {
    private static byte[] header = new byte[]{'#', '!', 'A', 'M', 'R', '\n'};
    private PipedInputStream pis = new PipedInputStream();
    private PipedOutputStream pos;
    private volatile boolean wroteHeader = false;

    protected Amr(PullTransport pullTransport, File dst) {
        super(pullTransport, dst);
        try {
            pos = new PipedOutputStream(pis);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override public void startRecording() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
/*                    if (!wroteHeader) {
                        synchronized (Amr.class) {
                            if (!wroteHeader) {
                                pos.write(header);
                                wroteHeader = true;
                            }
                        }
                    }*/
                    pullTransport.start(pos);
                    MediaCodec mediaCodec = initMediaCodec();
                    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

                    mediaCodec.start();
                    int simpleRate = mediaCodec.getInputFormat().getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    byte[] buffer = new byte[simpleRate];

                    FileOutputStream fos = new FileOutputStream(file);

                    boolean hasMoreData = true;
                    do {
                        int inputIndex = 0;
                        while (inputIndex != -1 && hasMoreData) {
                            hasMoreData = pullTransport.source().isEnableToBePulled();

                            inputIndex = mediaCodec.dequeueInputBuffer(-1);
                            ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputIndex);
                            inputBuffer.clear();

                            int len = pis.available();
                            if (len != -1) {
                                int readLen = pis.read(buffer, 0, len);
                                if (readLen != -1) {
                                    inputBuffer.put(buffer, 0, readLen);
                                    inputBuffer.limit(readLen);
                                    mediaCodec.queueInputBuffer(inputIndex, 0, readLen, 0, 0);
                                }
                            }
                        }
                    }
                    int outputIndex = 0;
                    while (outputIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);
                        if (outputIndex >= 0) {
                            ByteBuffer encodedData = mediaCodec.getOutputBuffer(outputIndex);
                            encodedData.position(bufferInfo.offset);
                            encodedData.limit(bufferInfo.offset + bufferInfo.size);
                            byte[] outData = new byte[bufferInfo.size];
                            encodedData.get(outData, 0, bufferInfo.size);
                            fos.write(outData, 0, bufferInfo.size);
                            mediaCodec.releaseOutputBuffer(outputIndex, false);
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    @Override public void stopRecording() {
        super.stopRecording();
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    @NonNull
    protected MediaFormat getMediaFormat(String sourceFile) throws IOException {
        MediaExtractor mex = new MediaExtractor();
        mex.setDataSource(sourceFile);
        return mex.getTrackFormat(0);
    }

    private MediaCodec initMediaCodec() throws IOException {
        AudioSource audioRecordSource = pullTransport.source();
        int sampleRateInHz = audioRecordSource.frequency();
        int channels = (audioRecordSource.channelPositionMask() == AudioFormat.CHANNEL_IN_MONO ? 1 : 2);
        byte bitsPerSample = audioRecordSource.bitsPerSample();

        MediaCodec mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AMR_NB);
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AMR_NB);
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRateInHz);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channels);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitsPerSample);
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        return mediaCodec;
    }

    /**
     *
     */
    public static abstract class AudioEncoder {

        public abstract void encode(String sourceFile);


    }
}
