package omrecorder;

import java.io.File;

import omrecorder.Pcm;
import omrecorder.PullTransport;
import omrecorder.Recorder;
import omrecorder.Wav;
import omrecorder.ex.Amr;

public class OmRecorderEx {
    private OmRecorderEx() {
    }

    public static Recorder pcm(PullTransport pullTransport, File file) {
        return new Pcm(pullTransport, file);
    }

    public static Recorder wav(PullTransport pullTransport, File file) {
        return new Wav(pullTransport, file);
    }

    public static Recorder amr(PullTransport pullTransport, File file) {
        return new Amr(pullTransport, file);
    }
}
