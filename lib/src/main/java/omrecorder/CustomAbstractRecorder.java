package omrecorder;

import java.io.File;

public abstract class CustomAbstractRecorder extends AbstractRecorder{
    protected CustomAbstractRecorder(PullTransport pullTransport, File file) {
        super(pullTransport, file);
    }
}
