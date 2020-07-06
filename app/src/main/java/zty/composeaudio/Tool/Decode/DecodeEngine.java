package zty.composeaudio.Tool.Decode;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.Tool.Global.Constant;
import com.Tool.Global.Variable;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import com.Tool.Function.CommonFunction;
import com.Tool.Function.FileFunction;
import com.Tool.Function.LogFunction;

import vavi.sound.pcm.resampling.ssrc.SSRC;
import zty.composeaudio.Tool.Interface.DecodeOperateInterface;

/**
 * Created by 郑童宇 on 2016/03/04.
 */
public class DecodeEngine {
    public static DecodeEngine instance;

    public DecodeEngine() {
    }

    public static DecodeEngine getInstance() {
        if (instance == null) {
            synchronized (DecodeEngine.class) {
                if (instance == null) {
                    instance = new DecodeEngine();
                }
            }
        }

        return instance;
    }

    public void beginDecodeMusicFile(String musicFileUrl, String decodeFileUrl, int startSecond,
                                     int endSecond,
                                     final DecodeOperateInterface decodeOperateInterface) {
        Handler handler = new Handler(Looper.getMainLooper());

        final boolean decodeResult =
                decodeMusicFile(musicFileUrl, decodeFileUrl, startSecond, endSecond, handler,
                        decodeOperateInterface);

        handler.post(new Runnable() {
            @Override
            public void run() {
                if (decodeResult) {
                    decodeOperateInterface.decodeSuccess();
                } else {
                    decodeOperateInterface.decodeFail();
                }
            }
        });
    }

    /***
     * 将音乐文件解码
     * @param musicFileUrl 源文件路径
     * @param decodeFileUrl 解码文件路径
     * @param startSecond 开始时间
     * @param endSecond 结束时间
     * @param handler
     * @param decodeOperateInterface 解码过程回调
     * @return
     */
    private boolean decodeMusicFile(String musicFileUrl, String decodeFileUrl, int startSecond,
                                    int endSecond,
                                    Handler handler,
                                    DecodeOperateInterface decodeOperateInterface) {
        int sampleRate = 0;
        int channelCount = 0;

        long duration = 0;

        String mime = null;

        //MediaExtractor, MediaFormat, MediaCodec
        MediaExtractor mediaExtractor = new MediaExtractor();
        MediaFormat mediaFormat = null;
        MediaCodec mediaCodec = null;

        //给媒体信息提取器设置源音频文件路径
        try {
            mediaExtractor.setDataSource(musicFileUrl);
        } catch (Exception e) {
            LogFunction.error("设置解码音频文件路径错误", e);
            return false;
        }

        // 获取音频格式轨信息
        mediaFormat = mediaExtractor.getTrackFormat(0);

        // 从音频格式轨信息中读取 采样率，声道数，时长，音频文件类型
        sampleRate = mediaFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE) ?
                mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        channelCount = mediaFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ?
                mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
        duration = mediaFormat.containsKey(MediaFormat.KEY_DURATION) ? mediaFormat.getLong
                (MediaFormat.KEY_DURATION)
                : 0;
        mime = mediaFormat.containsKey(MediaFormat.KEY_MIME) ? mediaFormat.getString(MediaFormat
                .KEY_MIME) : "";

        LogFunction.log("歌曲信息",
                "Track info: mime:" + mime + " 采样率sampleRate:" + sampleRate + " channels:" +
                        channelCount + " duration:" + duration);

        if (CommonFunction.isEmpty(mime) || !mime.startsWith("audio/")) {
            LogFunction.error("解码文件不是音频文件", "mime:" + mime);
            return false;
        }

        if (mime.equals("audio/ffmpeg")) {
            mime = "audio/mpeg";
            mediaFormat.setString(MediaFormat.KEY_MIME, mime);
        }

        //创建一个解码器
        try {
            mediaCodec = MediaCodec.createDecoderByType(mime);

            mediaCodec.configure(mediaFormat, null, null, 0);
        } catch (Exception e) {
            LogFunction.error("解码器configure出错", e);
            return false;
        }

        //后续解码操作
        getDecodeData(mediaExtractor, mediaCodec, decodeFileUrl, sampleRate, channelCount,
                startSecond,
                endSecond, handler, decodeOperateInterface);
        return true;
    }

    /**
     * decodeMusicFile 中调用的具体解码过程
     * @param mediaExtractor
     * @param mediaCodec
     * @param decodeFileUrl PCM文件路径
     * @param sampleRate
     * @param channelCount
     * @param startSecond
     * @param endSecond
     * @param handler
     * @param decodeOperateInterface
     */
    private void getDecodeData(MediaExtractor mediaExtractor, MediaCodec mediaCodec, String
            decodeFileUrl, int sampleRate,
                               int channelCount, int startSecond, int endSecond,
                               Handler handler,
                               final DecodeOperateInterface decodeOperateInterface) {

        //初始化解码状态，未解析完成
        boolean decodeInputEnd = false;
        boolean decodeOutputEnd = false;

        //当前读取采样数据的大小
        int sampleDataSize;

        //当前输入数据的ByteBuffer序号，当前输出数据的ByteBuffer序号
        int inputBufferIndex;
        int outputBufferIndex;

        //音频文件的采样位数字节数，= 采样位数/8
        int byteNumber;

        //上一次的解码操作时间，当前解码操作时间，用于通知回调接口
        long decodeNoticeTime = System.currentTimeMillis();
        long decodeTime;

        //当前采样的音频时间，比如在当前音频的第40秒的时候
        long presentationTimeUs = 0;

        //定义编解码的超时时间
        final long timeOutUs = 100;
        final long startMicroseconds = startSecond * 1000 * 1000;
        final long endMicroseconds = endSecond * 1000 * 1000;

        //存储输入数据的ByteBuffer数组，输出数据的ByteBuffer数组
        ByteBuffer[] inputBuffers;
        ByteBuffer[] outputBuffers;

        //当前编解码器操作的 输入数据ByteBuffer 和 输出数据ByteBuffer，可以从targetBuffer中获取解码后的PCM数据
        ByteBuffer sourceBuffer;
        ByteBuffer targetBuffer;

        //获取输出音频的媒体格式信息
        MediaFormat outputFormat = mediaCodec.getOutputFormat();

        MediaCodec.BufferInfo bufferInfo;

        byteNumber =
                (outputFormat.containsKey("bit-width") ? outputFormat.getInteger("bit-width") :
                        0) / 8;

        //开始解码操作
        mediaCodec.start();

        //获取存储输入数据的ByteBuffer数组，输出数据的ByteBuffer数组
        inputBuffers = mediaCodec.getInputBuffers();
        outputBuffers = mediaCodec.getOutputBuffers();

        mediaExtractor.selectTrack(0);

        //当前解码的缓存信息，里面的有效数据在offset和offset+size之间
        bufferInfo = new MediaCodec.BufferInfo();

        //获取解码后文件的输出流
        BufferedOutputStream bufferedOutputStream = FileFunction
                .GetBufferedOutputStreamFromFile(decodeFileUrl);


        //开始进入循环解码操作，判断读入源音频数据是否完成，输出解码音频数据是否完成
        while (!decodeOutputEnd) {
            if (decodeInputEnd) {
                return;
            }

            decodeTime = System.currentTimeMillis();

            //间隔1秒通知解码进度
            if (decodeTime - decodeNoticeTime > Constant.OneSecond) {
                final int decodeProgress =
                        (int) ((presentationTimeUs - startMicroseconds) * Constant
                                .NormalMaxProgress /
                                endMicroseconds);

                if (decodeProgress > 0) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            decodeOperateInterface.updateDecodeProgress(decodeProgress);
                        }
                    });
                }

                decodeNoticeTime = decodeTime;
            }

            try {
                //操作解码输入数据

                //从队列中获取当前解码器处理输入数据的ByteBuffer序号
                inputBufferIndex = mediaCodec.dequeueInputBuffer(timeOutUs);

                if (inputBufferIndex >= 0) {
                    //取得当前解码器处理输入数据的ByteBuffer
                    sourceBuffer = inputBuffers[inputBufferIndex];

                    //获取当前ByteBuffer，编解码器读取了多少采样数据
                    sampleDataSize = mediaExtractor.readSampleData(sourceBuffer, 0);

                    // setDataSource(String path)：即可以设置本地文件又可以设置网络文件
                    // readSampleData(ByteBuffer byteBuf, int offset)：把指定通道中的数据按偏移量读取到ByteBuffer中；
                    Log.e("通道中的数据读取到ByteBuffer中", String.valueOf(sourceBuffer));

                    //如果当前读取的采样数据<0，说明已经完成了读取操作
                    if (sampleDataSize < 0) {
                        decodeInputEnd = true;
                        sampleDataSize = 0;
                    } else {
                        presentationTimeUs = mediaExtractor.getSampleTime();
                    }

                    //然后将当前ByteBuffer重新加入到队列中交给编解码器做下一步读取操作
                    mediaCodec.queueInputBuffer(inputBufferIndex, 0, sampleDataSize,
                            presentationTimeUs,
                            decodeInputEnd ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);

                    //前进到下一段采样数据
                    if (!decodeInputEnd) {
                        mediaExtractor.advance();
                    }
                } else {
                    LogFunction.error("inputBufferIndex", "" + inputBufferIndex);
                }

                //操作解码输出数据

                //从队列中获取当前解码器处理输出数据的ByteBuffer序号
                // decode to PCM and push it to the AudioTrack player
                outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, timeOutUs);

                if (outputBufferIndex < 0) {
                    //输出ByteBuffer序号<0，可能是输出缓存变化了，输出格式信息变化了
                    switch (outputBufferIndex) {
                        case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                            outputBuffers = mediaCodec.getOutputBuffers();
                            LogFunction.error("MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED",
                                    "[AudioDecoder]output buffers have changed.");
                            break;
                        case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                            outputFormat = mediaCodec.getOutputFormat();

                            sampleRate = outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE) ?
                                    outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) :
                                    sampleRate;
                            channelCount = outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ?
                                    outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) :
                                    channelCount;
                            byteNumber = (outputFormat.containsKey("bit-width") ? outputFormat
                                    .getInteger
                                            ("bit-width") : 0) / 8;

                            LogFunction.error("MediaCodec.INFO_OUTPUT_FORMAT_CHANGED",
                                    "[AudioDecoder]output format has changed to " +
                                            mediaCodec.getOutputFormat());
                            break;
                        default:
                            LogFunction.error("error",
                                    "[AudioDecoder] dequeueOutputBuffer returned " +
                                            outputBufferIndex);
                            break;
                    }
                    continue;
                }

                //取得当前解码器处理输出数据的ByteBuffer
                targetBuffer = outputBuffers[outputBufferIndex];

                byte[] sourceByteArray = new byte[bufferInfo.size];

                //将解码后的targetBuffer中的数据复制到sourceByteArray中
                targetBuffer.get(sourceByteArray);
                targetBuffer.clear();

                //释放当前的输出缓存
                mediaCodec.releaseOutputBuffer(outputBufferIndex, false);

                //判断当前是否解码数据全部结束了
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    decodeOutputEnd = true;
                }

                //sourceByteArray就是最终解码后的采样数据
                //接下来可以对这些数据进行采样位数，声道的转换，但这是可选的，默认是和源音频一样的声道和采样位数
                if (sourceByteArray.length > 0 && bufferedOutputStream != null) {
                    if (presentationTimeUs < startMicroseconds) {
                        continue;
                    }


                    //采样位数转换，按自己需要是否实现
                    byte[] convertByteNumberByteArray = ConvertByteNumber(byteNumber, Constant
                                    .RecordByteNumber,
                            sourceByteArray);

                    //声道转换，按自己需要是否实现
                    byte[] resultByteArray =
                            ConvertChannelNumber(channelCount, Constant.RecordChannelNumber,
                                    Constant.RecordByteNumber,
                                    convertByteNumberByteArray);

                    //将解码后的PCM数据写入到PCM文件
                    try {
                        bufferedOutputStream.write(resultByteArray);
                    } catch (Exception e) {
                        LogFunction.error("输出解压音频数据异常", e);
                    }
                }

                if (presentationTimeUs > endMicroseconds) {
                    break;
                }
            } catch (Exception e) {
                LogFunction.error("getDecodeData异常", e);
            }
        }

        if (bufferedOutputStream != null) {
            try {
                bufferedOutputStream.close();
            } catch (IOException e) {
                LogFunction.error("关闭bufferedOutputStream异常", e);
            }
        }

        // 重置采样率，按自己需要是否实现
        if (sampleRate != Constant.RecordSampleRate) {
            Resample(sampleRate, decodeFileUrl);
        }

        if (mediaCodec != null) {
            mediaCodec.stop();
            mediaCodec.release();
        }

        //释放mediaCodec 和 mediaExtractor
        if (mediaExtractor != null) {
            mediaExtractor.release();
        }
    }

    private static void Resample(int sampleRate, String decodeFileUrl) {
        String newDecodeFileUrl = decodeFileUrl + "new";

        try {
            FileInputStream fileInputStream =
                    new FileInputStream(new File(decodeFileUrl));
            FileOutputStream fileOutputStream =
                    new FileOutputStream(new File(newDecodeFileUrl));

            new SSRC(fileInputStream, fileOutputStream, sampleRate, Constant.RecordSampleRate,
                    Constant.RecordByteNumber, Constant.RecordByteNumber, 1, Integer.MAX_VALUE,
                    0, 0, true);

            fileInputStream.close();
            fileOutputStream.close();

            FileFunction.RenameFile(newDecodeFileUrl, decodeFileUrl);
        } catch (IOException e) {
            LogFunction.error("关闭bufferedOutputStream异常", e);
        }
    }

    private static byte[] ConvertByteNumber(int sourceByteNumber, int outputByteNumber, byte[]
            sourceByteArray) {
        if (sourceByteNumber == outputByteNumber) {
            return sourceByteArray;
        }

        int sourceByteArrayLength = sourceByteArray.length;

        byte[] byteArray;

        switch (sourceByteNumber) {
            case 1:
                switch (outputByteNumber) {
                    case 2:
                        byteArray = new byte[sourceByteArrayLength * 2];

                        byte resultByte[];

                        for (int index = 0; index < sourceByteArrayLength; index += 1) {
                            resultByte = CommonFunction.GetBytes((short) (sourceByteArray[index]
                                    * 256), Variable
                                    .isBigEnding);

                            byteArray[2 * index] = resultByte[0];
                            byteArray[2 * index + 1] = resultByte[1];
                        }

                        return byteArray;
                }
                break;
            case 2:
                switch (outputByteNumber) {
                    case 1:
                        int outputByteArrayLength = sourceByteArrayLength / 2;

                        byteArray = new byte[outputByteArrayLength];

                        for (int index = 0; index < outputByteArrayLength; index += 1) {
                            byteArray[index] = (byte) (CommonFunction.GetShort(sourceByteArray[2
                                            * index],
                                    sourceByteArray[2 * index + 1], Variable.isBigEnding) / 256);
                        }

                        return byteArray;
                }
                break;
        }

        return sourceByteArray;
    }

    private static byte[] ConvertChannelNumber(int sourceChannelCount, int outputChannelCount,
                                               int byteNumber,
                                               byte[] sourceByteArray) {
        if (sourceChannelCount == outputChannelCount) {
            return sourceByteArray;
        }

        switch (byteNumber) {
            case 1:
            case 2:
                break;
            default:
                return sourceByteArray;
        }

        int sourceByteArrayLength = sourceByteArray.length;

        byte[] byteArray;

        switch (sourceChannelCount) {
            case 1:
                switch (outputChannelCount) {
                    case 2:
                        byteArray = new byte[sourceByteArrayLength * 2];

                        byte firstByte;
                        byte secondByte;

                        switch (byteNumber) {
                            case 1:
                                for (int index = 0; index < sourceByteArrayLength; index += 1) {
                                    firstByte = sourceByteArray[index];

                                    byteArray[2 * index] = firstByte;
                                    byteArray[2 * index + 1] = firstByte;
                                }
                                break;
                            case 2:
                                for (int index = 0; index < sourceByteArrayLength; index += 2) {
                                    firstByte = sourceByteArray[index];
                                    secondByte = sourceByteArray[index + 1];

                                    byteArray[2 * index] = firstByte;
                                    byteArray[2 * index + 1] = secondByte;
                                    byteArray[2 * index + 2] = firstByte;
                                    byteArray[2 * index + 3] = secondByte;
                                }
                                break;
                        }

                        return byteArray;
                }
                break;
            case 2:
                switch (outputChannelCount) {
                    case 1:
                        int outputByteArrayLength = sourceByteArrayLength / 2;

                        byteArray = new byte[outputByteArrayLength];

                        switch (byteNumber) {
                            case 1:
                                for (int index = 0; index < outputByteArrayLength; index += 2) {
                                    short averageNumber =
                                            (short) ((short) sourceByteArray[2 * index] + (short)
                                                    sourceByteArray[2 *
                                                            index + 1]);
                                    byteArray[index] = (byte) (averageNumber >> 1);
                                }
                                break;
                            case 2:
                                for (int index = 0; index < outputByteArrayLength; index += 2) {
                                    byte resultByte[] = CommonFunction.AverageShortByteArray
                                            (sourceByteArray[2 * index],
                                                    sourceByteArray[2 * index + 1],
                                                    sourceByteArray[2 *
                                                            index + 2],
                                                    sourceByteArray[2 * index + 3], Variable
                                                            .isBigEnding);

                                    byteArray[index] = resultByte[0];
                                    byteArray[index + 1] = resultByte[1];
                                }
                                break;
                        }

                        return byteArray;
                }
                break;
        }

        return sourceByteArray;
    }
}