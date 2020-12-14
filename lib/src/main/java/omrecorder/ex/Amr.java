package omrecorder.ex;


import android.annotation.TargetApi;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;

import omrecorder.AudioSource;
import omrecorder.CustomAbstractRecorder;
import omrecorder.PullTransport;

public class Amr extends CustomAbstractRecorder {
    private static byte[] header = new byte[]{'#', '!', 'A', 'M', 'R', '\n'};
    private PipedInputStream pis = new PipedInputStream(1024 * 1024 * 50);
    private PipedOutputStream pos;
    private volatile boolean wroteHeader = false;

    public Amr(PullTransport pullTransport, File dst) {
        super(pullTransport, dst);
        try {
            pos = new PipedOutputStream(pis);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * byte数组转16进制字符串
     *
     * @param bytes byte数组
     * @return 16进制字符串
     */
    public static String byteArrayToHexStr(byte[] bytes) {
        String strHex;
        StringBuilder sb = new StringBuilder();
        for (byte aByte : bytes) {
            strHex = Integer.toHexString(aByte & 0xFF);
            sb.append(" ").append((strHex.length() == 1) ? "0" : "").append(strHex); // 每个字节由两个字符表示，位数不够，高位补0
        }
        return sb.toString().trim();
    }

    @Override
    public void startRecording() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    pullTransport.start(pos);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long startNano = System.nanoTime();
                    long lastTime = 0;
                    MediaCodec mediaCodec = initMediaCodec();
                    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                    bufferInfo.size = 65534;
                    mediaCodec.start();
                    int simpleRate = mediaCodec.getInputFormat().getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    byte[] buffer = new byte[simpleRate];

                    FileOutputStream fos = new FileOutputStream(file);
                    double presentationTimeUs = 0;
                    long totalBytesRead = 0;

                    boolean hasMoreData = true;
                    if (!wroteHeader) {
                        synchronized (Amr.class) {
                            if (!wroteHeader) {
                                Log.i(Amr.class.getSimpleName(), String.format(Locale.getDefault(), "    ---Wrote the file header: %s---", byteArrayToHexStr(header)));
                                fos.write(header);
                                wroteHeader = true;
                            }
                        }
                    }
                    long writeTimes = 0;
                    do {
                        int inputIndex = 0;
                        while (true) {
                            hasMoreData = pullTransport.source().isEnableToBePulled();
                            hasMoreData = hasMoreData || pis.available() < 0;
                            if (!hasMoreData) {
                                inputIndex = mediaCodec.dequeueInputBuffer(0);
                                Log.i(Amr.class.getSimpleName(), String.format(Locale.getDefault(), "=======Loop inputIndex:%d======", inputIndex));
                                presentationTimeUs = (System.nanoTime() - startNano) / 1000.0;
                                if (inputIndex >= 0) {
                                    mediaCodec.queueInputBuffer(inputIndex, 0, 0, (long) presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                    Log.i(Amr.class.getSimpleName(), String.format(Locale.getDefault(), "=========Wrote EOF in to mediaCodec, wroteTimes is %s=========", Long.toString(writeTimes)));
                                    return;
                                } else {
                                    writeByteToFile(mediaCodec, bufferInfo, fos, presentationTimeUs);
//                                    writeTimes++;
                                    continue;
                                }
                            }
                            int len = 1;
                            len = pis.available();
                            if (len <= 0) {
                                continue;
                            }

                            if ((System.currentTimeMillis() - lastTime) < 1000 || len < 655350 / 2) {
                                try {
                                    Thread.sleep(1000L);
                                    lastTime = System.currentTimeMillis();
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                            inputIndex = mediaCodec.dequeueInputBuffer(0);
                            if (inputIndex < 0) {
                                writeByteToFile(mediaCodec, bufferInfo, fos, presentationTimeUs);
//                                writeTimes++;
                                continue;
                            }
                            Log.i(Amr.class.getSimpleName(), String.format(Locale.getDefault(), "=======Loop inputIndex:%d======", inputIndex));
                            ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputIndex);
                            inputBuffer.clear();

                            Log.i(Amr.class.getSimpleName(), String.format(Locale.getDefault(), "    ---PipedInputStream has %d data---", len));
                            int readLen = pis.read(buffer);
                            if (readLen > 0) {
                                totalBytesRead += len;
                                inputBuffer.put(buffer, 0, readLen);
                                presentationTimeUs = (System.nanoTime() - startNano) / 1000.0;
                                mediaCodec.queueInputBuffer(inputIndex, 0, readLen, (long) presentationTimeUs, 0);
                                writeTimes++;
                                Log.i(Amr.class.getSimpleName(), String.format(Locale.getDefault(), "    ---presentationTimeUs is %s second---", Double.toString(presentationTimeUs / 1000)));
                            }
                            Log.i(Amr.class.getSimpleName(), String.format(Locale.getDefault(), "    ---read %d data---", readLen));
                            writeByteToFile(mediaCodec, bufferInfo, fos, presentationTimeUs);
//                            writeTimes++;
                        }
                    }
                    while (bufferInfo.flags != MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            private void writeByteToFile(MediaCodec mediaCodec, MediaCodec.BufferInfo bufferInfo, FileOutputStream fos, double presentationTimeUs) throws IOException {
                int outputIndex = 0;
                while (outputIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);
                    Log.i(Amr.class.getSimpleName(), String.format(Locale.getDefault(), "=======Loop outputIndex:%d======", outputIndex));
                    if (outputIndex >= 0) {
                        ByteBuffer encodedData = mediaCodec.getOutputBuffer(outputIndex);
                        encodedData.position(bufferInfo.offset);
                        encodedData.limit(bufferInfo.offset + bufferInfo.size);
                        byte[] outData = new byte[bufferInfo.size];
                        Log.i(Amr.class.getSimpleName(), String.format(Locale.getDefault(), "    ---ByteBuffer length is %d---", bufferInfo.size));
                        encodedData.get(outData, 0, bufferInfo.size);
                        fos.write(outData, 0, bufferInfo.size);
                        Log.i(Amr.class.getSimpleName(), String.format(Locale.getDefault(), "    ---Wrote to file success, data:%s---", byteArrayToHexStr(outData)));
                        mediaCodec.releaseOutputBuffer(outputIndex, false);
                    }
                }
            }
        }).start();
    }

    @Override
    public void stopRecording() {
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
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 655360);

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
