/*
 * Main entry of app process.
 * 
 * Starts the interpreted runtime, then starts up the application.
 * 
 */

#define LOG_TAG "appproc"

#include <binder/IPCThreadState.h>
#include <binder/ProcessState.h>
#include <utils/Log.h>
#include <cutils/process_name.h>
#include <cutils/memory.h>
#include <android_runtime/AndroidRuntime.h>

#include <stdio.h>
#include <unistd.h>

namespace android {

void app_usage()
{
    fprintf(stderr,
        "Usage: app_process [java-options] cmd-dir start-class-name [options]\n");
}

status_t app_init(const char* className, int argc, const char* const argv[])
{
    LOGV("Entered app_init()!\n");

    AndroidRuntime* jr = AndroidRuntime::getRuntime();
    jr->callMain(className, argc, argv);
    
    LOGV("Exiting app_init()!\n");
    return NO_ERROR;
}

class AppRuntime : public AndroidRuntime
{
public:
    AppRuntime()
        : mParentDir(NULL)
        , mClassName(NULL)
        , mArgC(0)
        , mArgV(NULL)
    {
    }

#if 0
    // this appears to be unused
    const char* getParentDir() const
    {
        return mParentDir;
    }
#endif

    const char* getClassName() const
    {
        return mClassName;
    }

    virtual void onStarted()
    {
        sp<ProcessState> proc = ProcessState::self();
        if (proc->supportsProcesses()) {
            LOGV("App process: starting thread pool.\n");
            proc->startThreadPool();
        }
        
        app_init(mClassName, mArgC, mArgV);

        if (ProcessState::self()->supportsProcesses()) {
            IPCThreadState::self()->stopProcess();
        }
    }
		//这个函数主要在SystemServer进程执行java层的RuntimeInit的zygoteInitNative函数进来的。
    virtual void onZygoteInit()
    {
        sp<ProcessState> proc = ProcessState::self();
        if (proc->supportsProcesses()) {
            LOGV("App process: starting thread pool.\n");
            proc->startThreadPool();
        }       
    }

    virtual void onExit(int code)
    {
        if (mClassName == NULL) {
            // if zygote
            if (ProcessState::self()->supportsProcesses()) {
                IPCThreadState::self()->stopProcess();
            }
        }

        AndroidRuntime::onExit(code);
    }

    
    const char* mParentDir;
    const char* mClassName;
    int mArgC;
    const char* const* mArgV;
};

}

using namespace android;

/*const_cast会去掉或者加上字符指针的const属性
 * sets argv0 to as much of newArgv0 as will fit
 */
static void setArgv0(const char *argv0, const char *newArgv0)
{
    strlcpy(const_cast<char *>(argv0), newArgv0, strlen(argv0));
}

int main(int argc, const char* const argv[])
{
	//在ProcessState.cpp里面有全局变量保存这个参数值，
    // These are global variables in ProcessState.cpp
    mArgC = argc;
    mArgV = argv;
    
    mArgLen = 0;
    for (int i=0; i<argc; i++) {
        mArgLen += strlen(argv[i]) + 1;
    }
    mArgLen--;

    AppRuntime runtime;
    const char *arg;
    const char *argv0;
		//我们考虑init.rc里面的service zygote /system/bin/app_process -Xzygote /system/bin --zygote --start-system-server
		//那么执行的时候就变成了zygote /system/bin/app_process -Xzygote /system/bin --zygote --start-system-server
		//第一个参数就是zygote,
    argv0 = argv[0];//

    // Process command line arguments
    // ignore argv[0]
    //argv[0]其实是程序名，argv[1]才是真正的第一个参数，下面这俩行代码就是跳过第一个参数zygote
    argc--;
    argv++;

    // Everything up to '--' or first non '-' arg goes to the vm
    //下面这行代码i标识字符串/system/bin/app_process
    
    int i = runtime.addVmArguments(argc, argv);

    // Next arg is parent directory
    if (i < argc) {
    	//保存/system/bin/app_process
        runtime.mParentDir = argv[i++];
    }

    // Next arg is startup classname or "--zygote"
    if (i < argc) {
        arg = argv[i++];
        if (0 == strcmp("--zygote", arg)) {
            bool startSystemServer = (i < argc) ? 
                    strcmp(argv[i], "--start-system-server") == 0 : false;
            setArgv0(argv0, "zygote");
            set_process_name("zygote");
            runtime.start("com.android.internal.os.ZygoteInit",
                startSystemServer);
        } else {
            set_process_name(argv0);

            runtime.mClassName = arg;

            // Remainder of args get passed to startup class main()
            runtime.mArgC = argc-i;
            runtime.mArgV = argv+i;

            LOGV("App process is starting with pid=%d, class=%s.\n",
                 getpid(), runtime.getClassName());
            runtime.start();
        }
    } else {
        LOG_ALWAYS_FATAL("app_process: no class name or --zygote supplied.");
        fprintf(stderr, "Error: no class name or --zygote supplied.\n");
        app_usage();
        return 10;
    }

}
