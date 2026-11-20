package android.microntek.mtcser;

public interface BTServiceInf {
    abstract class Stub extends android.os.Binder {
        static final int TRANSACTION_init = 1;
        static final int TRANSACTION_getBTState = 2;
        static final int TRANSACTION_getAVState = 3;
        static final int TRANSACTION_getNowDevName = 12;
        static final int TRANSACTION_avPlay = 14;
        static final int TRANSACTION_avPlayPause = 15;
        static final int TRANSACTION_avPlayStop = 16;
        static final int TRANSACTION_avPlayPrev = 17;
        static final int TRANSACTION_avPlayNext = 18;
        static final int TRANSACTION_deleteBT = 41;
        static final int TRANSACTION_deleteHistoryAll = 49;
        static final int TRANSACTION_musicMute = 50;
        static final int TRANSACTION_musicUnmute = 51;
        static final int TRANSACTION_reDial = 56;
        static final int TRANSACTION_getMusicInfo = 57;
        static final int TRANSACTION_getOBDstate = 58;
        static final int TRANSACTION_requestBtInfo = 59;
    }
}
