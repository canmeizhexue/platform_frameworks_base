/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app;

import com.android.internal.util.ArrayUtils;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.AndroidRuntimeException;
import android.util.Slog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;

final class IntentReceiverLeaked extends AndroidRuntimeException {
    public IntentReceiverLeaked(String msg) {
        super(msg);
    }
}

final class ServiceConnectionLeaked extends AndroidRuntimeException {
    public ServiceConnectionLeaked(String msg) {
        super(msg);
    }
}

/**本地维护的一个apk
 * Local state maintained about a currently loaded .apk.
 * @hide
 */
final class LoadedApk {

    private final ActivityThread mActivityThread;
    private final ApplicationInfo mApplicationInfo;
    final String mPackageName;
    private final String mAppDir;
    private final String mResDir;
    private final String[] mSharedLibraries;
    private final String mDataDir;
    private final String mLibDir;
    private final File mDataDirFile;
    private final ClassLoader mBaseClassLoader;
    private final boolean mSecurityViolation;
    private final boolean mIncludeCode;
    Resources mResources;
    private ClassLoader mClassLoader;
    private Application mApplication;
    CompatibilityInfo mCompatibilityInfo;

    private final HashMap<Context, HashMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher>> mReceivers
        = new HashMap<Context, HashMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher>>();
    private final HashMap<Context, HashMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher>> mUnregisteredReceivers
    = new HashMap<Context, HashMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher>>();
    private final HashMap<Context, HashMap<ServiceConnection, LoadedApk.ServiceDispatcher>> mServices
        = new HashMap<Context, HashMap<ServiceConnection, LoadedApk.ServiceDispatcher>>();
    private final HashMap<Context, HashMap<ServiceConnection, LoadedApk.ServiceDispatcher>> mUnboundServices
        = new HashMap<Context, HashMap<ServiceConnection, LoadedApk.ServiceDispatcher>>();

    int mClientCount = 0;

    Application getApplication() {
        return mApplication;
    }
		//对于一般的应用程序使用的是这个构造函数，
    public LoadedApk(ActivityThread activityThread, ApplicationInfo aInfo,
            ActivityThread mainThread, ClassLoader baseLoader,
            boolean securityViolation, boolean includeCode) {
        mActivityThread = activityThread;
        mApplicationInfo = aInfo;
        mPackageName = aInfo.packageName;
        
        //
        mAppDir = aInfo.sourceDir;
        //因为有可能一个应用程序，开多个进程了，，，
        mResDir = aInfo.uid == Process.myUid() ? aInfo.sourceDir
                : aInfo.publicSourceDir;
        mSharedLibraries = aInfo.sharedLibraryFiles;
        mDataDir = aInfo.dataDir;
        mDataDirFile = mDataDir != null ? new File(mDataDir) : null;
        mLibDir = aInfo.nativeLibraryDir;
        mBaseClassLoader = baseLoader;
        mSecurityViolation = securityViolation;
        mIncludeCode = includeCode;
        mCompatibilityInfo = new CompatibilityInfo(aInfo);

        if (mAppDir == null) {
        	//一般不满足，，，
            if (ActivityThread.mSystemContext == null) {
                ActivityThread.mSystemContext =
                    ContextImpl.createSystemContext(mainThread);
                ActivityThread.mSystemContext.getResources().updateConfiguration(
                         mainThread.getConfiguration(),
                         mainThread.getDisplayMetricsLocked(false));
                //Slog.i(TAG, "Created system resources "
                //        + mSystemContext.getResources() + ": "
                //        + mSystemContext.getResources().getConfiguration());
            }
            mClassLoader = ActivityThread.mSystemContext.getClassLoader();
            mResources = ActivityThread.mSystemContext.getResources();
        }
    }
		//这个构造函数只有在系统SystemServer进程构造ContextImpl的时候才会调用
    public LoadedApk(ActivityThread activityThread, String name,
            Context systemContext, ApplicationInfo info) {
        mActivityThread = activityThread;
        mApplicationInfo = info != null ? info : new ApplicationInfo();
        mApplicationInfo.packageName = name;
        mPackageName = name;
        mAppDir = null;
        mResDir = null;
        mSharedLibraries = null;
        mDataDir = null;
        mDataDirFile = null;
        mLibDir = null;
        mBaseClassLoader = null;
        mSecurityViolation = false;
        mIncludeCode = true;
        mClassLoader = systemContext.getClassLoader();
        mResources = systemContext.getResources();
        mCompatibilityInfo = new CompatibilityInfo(mApplicationInfo);
    }

    public String getPackageName() {
        return mPackageName;
    }

    public ApplicationInfo getApplicationInfo() {
        return mApplicationInfo;
    }

    public boolean isSecurityViolation() {
        return mSecurityViolation;
    }

    /**
     * Gets the array of shared libraries that are listed as
     * used by the given package.
     *
     * @param packageName the name of the package (note: not its
     * file name)
     * @return null-ok; the array of shared libraries, each one
     * a fully-qualified path
     */
    private static String[] getLibrariesFor(String packageName) {
        ApplicationInfo ai = null;
        try {
            ai = ActivityThread.getPackageManager().getApplicationInfo(packageName,
                    PackageManager.GET_SHARED_LIBRARY_FILES);
        } catch (RemoteException e) {
            throw new AssertionError(e);
        }

        if (ai == null) {
            return null;
        }

        return ai.sharedLibraryFiles;
    }

    /**
     * Combines two arrays (of library names) such that they are
     * concatenated in order but are devoid of duplicates. The
     * result is a single string with the names of the libraries
     * separated by colons, or <code>null</code> if both lists
     * were <code>null</code> or empty.
     *
     * @param list1 null-ok; the first list
     * @param list2 null-ok; the second list
     * @return null-ok; the combination
     */
    private static String combineLibs(String[] list1, String[] list2) {
        StringBuilder result = new StringBuilder(300);
        boolean first = true;

        if (list1 != null) {
            for (String s : list1) {
                if (first) {
                    first = false;
                } else {
                    result.append(':');
                }
                result.append(s);
            }
        }

        // Only need to check for duplicates if list1 was non-empty.
        boolean dupCheck = !first;

        if (list2 != null) {
            for (String s : list2) {
                if (dupCheck && ArrayUtils.contains(list1, s)) {
                    continue;
                }

                if (first) {
                    first = false;
                } else {
                    result.append(':');
                }
                result.append(s);
            }
        }

        return result.toString();
    }

    public ClassLoader getClassLoader() {
        synchronized (this) {
            if (mClassLoader != null) {
                return mClassLoader;
            }

            if (mIncludeCode && !mPackageName.equals("android")) {
                String zip = mAppDir;

                /*
                 * The following is a bit of a hack to inject
                 * instrumentation into the system: If the app
                 * being started matches one of the instrumentation names,
                 * then we combine both the "instrumentation" and
                 * "instrumented" app into the path, along with the
                 * concatenation of both apps' shared library lists.
                 */

                String instrumentationAppDir =
                        mActivityThread.mInstrumentationAppDir;
                String instrumentationAppPackage =
                        mActivityThread.mInstrumentationAppPackage;
                String instrumentedAppDir =
                        mActivityThread.mInstrumentedAppDir;
                String[] instrumentationLibs = null;

                if (mAppDir.equals(instrumentationAppDir)
                        || mAppDir.equals(instrumentedAppDir)) {
                    zip = instrumentationAppDir + ":" + instrumentedAppDir;
                    if (! instrumentedAppDir.equals(instrumentationAppDir)) {
                        instrumentationLibs =
                            getLibrariesFor(instrumentationAppPackage);
                    }
                }

                if ((mSharedLibraries != null) ||
                        (instrumentationLibs != null)) {
                    zip =
                        combineLibs(mSharedLibraries, instrumentationLibs)
                        + ':' + zip;
                }

                /*
                 * With all the combination done (if necessary, actually
                 * create the class loader.
                 */

                if (ActivityThread.localLOGV)
                    Slog.v(ActivityThread.TAG, "Class path: " + zip + ", JNI path: " + mLibDir);

                mClassLoader =
                    ApplicationLoaders.getDefault().getClassLoader(
                        zip, mLibDir, mBaseClassLoader);
                initializeJavaContextClassLoader();
            } else {
                if (mBaseClassLoader == null) {
                    mClassLoader = ClassLoader.getSystemClassLoader();
                } else {
                    mClassLoader = mBaseClassLoader;
                }
            }
            return mClassLoader;
        }
    }

    /**
     * Setup value for Thread.getContextClassLoader(). If the
     * package will not run in in a VM with other packages, we set
     * the Java context ClassLoader to the
     * PackageInfo.getClassLoader value. However, if this VM can
     * contain multiple packages, we intead set the Java context
     * ClassLoader to a proxy that will warn about the use of Java
     * context ClassLoaders and then fall through to use the
     * system ClassLoader.
     *
     * <p> Note that this is similar to but not the same as the
     * android.content.Context.getClassLoader(). While both
     * context class loaders are typically set to the
     * PathClassLoader used to load the package archive in the
     * single application per VM case, a single Android process
     * may contain several Contexts executing on one thread with
     * their own logical ClassLoaders while the Java context
     * ClassLoader is a thread local. This is why in the case when
     * we have multiple packages per VM we do not set the Java
     * context ClassLoader to an arbitrary but instead warn the
     * user to set their own if we detect that they are using a
     * Java library that expects it to be set.
     */
    private void initializeJavaContextClassLoader() {
        IPackageManager pm = ActivityThread.getPackageManager();
        android.content.pm.PackageInfo pi;
        try {
            pi = pm.getPackageInfo(mPackageName, 0);
        } catch (RemoteException e) {
            throw new AssertionError(e);
        }
        /*
         * Two possible indications that this package could be
         * sharing its virtual machine with other packages:
         *
         * 1.) the sharedUserId attribute is set in the manifest,
         *     indicating a request to share a VM with other
         *     packages with the same sharedUserId.
         *
         * 2.) the application element of the manifest has an
         *     attribute specifying a non-default process name,
         *     indicating the desire to run in another packages VM.
         */
        boolean sharedUserIdSet = (pi.sharedUserId != null);
        boolean processNameNotDefault =
            (pi.applicationInfo != null &&
             !mPackageName.equals(pi.applicationInfo.processName));
        boolean sharable = (sharedUserIdSet || processNameNotDefault);
        ClassLoader contextClassLoader =
            (sharable)
            ? new WarningContextClassLoader()
            : mClassLoader;
        Thread.currentThread().setContextClassLoader(contextClassLoader);
    }

    private static class WarningContextClassLoader extends ClassLoader {

        private static boolean warned = false;

        private void warn(String methodName) {
            if (warned) {
                return;
            }
            warned = true;
            Thread.currentThread().setContextClassLoader(getParent());
            Slog.w(ActivityThread.TAG, "ClassLoader." + methodName + ": " +
                  "The class loader returned by " +
                  "Thread.getContextClassLoader() may fail for processes " +
                  "that host multiple applications. You should explicitly " +
                  "specify a context class loader. For example: " +
                  "Thread.setContextClassLoader(getClass().getClassLoader());");
        }

        @Override public URL getResource(String resName) {
            warn("getResource");
            return getParent().getResource(resName);
        }

        @Override public Enumeration<URL> getResources(String resName) throws IOException {
            warn("getResources");
            return getParent().getResources(resName);
        }

        @Override public InputStream getResourceAsStream(String resName) {
            warn("getResourceAsStream");
            return getParent().getResourceAsStream(resName);
        }

        @Override public Class<?> loadClass(String className) throws ClassNotFoundException {
            warn("loadClass");
            return getParent().loadClass(className);
        }

        @Override public void setClassAssertionStatus(String cname, boolean enable) {
            warn("setClassAssertionStatus");
            getParent().setClassAssertionStatus(cname, enable);
        }

        @Override public void setPackageAssertionStatus(String pname, boolean enable) {
            warn("setPackageAssertionStatus");
            getParent().setPackageAssertionStatus(pname, enable);
        }

        @Override public void setDefaultAssertionStatus(boolean enable) {
            warn("setDefaultAssertionStatus");
            getParent().setDefaultAssertionStatus(enable);
        }

        @Override public void clearAssertionStatus() {
            warn("clearAssertionStatus");
            getParent().clearAssertionStatus();
        }
    }

    public String getAppDir() {
        return mAppDir;
    }

    public String getResDir() {
        return mResDir;
    }

    public String getDataDir() {
        return mDataDir;
    }

    public File getDataDirFile() {
        return mDataDirFile;
    }

    public AssetManager getAssets(ActivityThread mainThread) {
        return getResources(mainThread).getAssets();
    }

    public Resources getResources(ActivityThread mainThread) {
    		//要是没有就重新建立，，，
        if (mResources == null) {
            mResources = mainThread.getTopLevelResources(mResDir, this);
        }
        return mResources;
    }
		//获取Application对象，，
    public Application makeApplication(boolean forceDefaultAppClass,
            Instrumentation instrumentation) {
        if (mApplication != null) {
            return mApplication;
        }

        Application app = null;

        String appClass = mApplicationInfo.className;
        if (forceDefaultAppClass || (appClass == null)) {
            appClass = "android.app.Application";
        }

        try {
            java.lang.ClassLoader cl = getClassLoader();
            //构造Application对象的时候构造了ContextImpl,,
            ContextImpl appContext = new ContextImpl();
            appContext.init(this, null, mActivityThread);
            app = mActivityThread.mInstrumentation.newApplication(
                    cl, appClass, appContext);
            appContext.setOuterContext(app);
        } catch (Exception e) {
            if (!mActivityThread.mInstrumentation.onException(app, e)) {
                throw new RuntimeException(
                    "Unable to instantiate application " + appClass
                    + ": " + e.toString(), e);
            }
        }
        mActivityThread.mAllApplications.add(app);
        mApplication = app;

        if (instrumentation != null) {
            try {
                instrumentation.callApplicationOnCreate(app);
            } catch (Exception e) {
                if (!instrumentation.onException(app, e)) {
                    throw new RuntimeException(
                        "Unable to create application " + app.getClass().getName()
                        + ": " + e.toString(), e);
                }
            }
        }
        
        return app;
    }

    public void removeContextRegistrations(Context context,
            String who, String what) {
        HashMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> rmap =
            mReceivers.remove(context);
        if (rmap != null) {
            Iterator<LoadedApk.ReceiverDispatcher> it = rmap.values().iterator();
            while (it.hasNext()) {
                LoadedApk.ReceiverDispatcher rd = it.next();
                IntentReceiverLeaked leak = new IntentReceiverLeaked(
                        what + " " + who + " has leaked IntentReceiver "
                        + rd.getIntentReceiver() + " that was " +
                        "originally registered here. Are you missing a " +
                        "call to unregisterReceiver()?");
                leak.setStackTrace(rd.getLocation().getStackTrace());
                Slog.e(ActivityThread.TAG, leak.getMessage(), leak);
                try {
                    ActivityManagerNative.getDefault().unregisterReceiver(
                            rd.getIIntentReceiver());
                } catch (RemoteException e) {
                    // system crashed, nothing we can do
                }
            }
        }
        mUnregisteredReceivers.remove(context);
        //Slog.i(TAG, "Receiver registrations: " + mReceivers);
        HashMap<ServiceConnection, LoadedApk.ServiceDispatcher> smap =
            mServices.remove(context);
        if (smap != null) {
            Iterator<LoadedApk.ServiceDispatcher> it = smap.values().iterator();
            while (it.hasNext()) {
                LoadedApk.ServiceDispatcher sd = it.next();
                ServiceConnectionLeaked leak = new ServiceConnectionLeaked(
                        what + " " + who + " has leaked ServiceConnection "
                        + sd.getServiceConnection() + " that was originally bound here");
                leak.setStackTrace(sd.getLocation().getStackTrace());
                Slog.e(ActivityThread.TAG, leak.getMessage(), leak);
                try {
                    ActivityManagerNative.getDefault().unbindService(
                            sd.getIServiceConnection());
                } catch (RemoteException e) {
                    // system crashed, nothing we can do
                }
                sd.doForget();
            }
        }
        mUnboundServices.remove(context);
        //Slog.i(TAG, "Service registrations: " + mServices);
    }

    public IIntentReceiver getReceiverDispatcher(BroadcastReceiver r,
            Context context, Handler handler,
            Instrumentation instrumentation, boolean registered) {
        synchronized (mReceivers) {
            LoadedApk.ReceiverDispatcher rd = null;
            HashMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> map = null;
            if (registered) {
                map = mReceivers.get(context);
                if (map != null) {
                    rd = map.get(r);
                }
            }
            if (rd == null) {
                rd = new ReceiverDispatcher(r, context, handler,
                        instrumentation, registered);
                if (registered) {
                    if (map == null) {
                        map = new HashMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher>();
                        mReceivers.put(context, map);
                    }
                    map.put(r, rd);
                }
            } else {
                rd.validate(context, handler);
            }
            return rd.getIIntentReceiver();
        }
    }

    public IIntentReceiver forgetReceiverDispatcher(Context context,
            BroadcastReceiver r) {
        synchronized (mReceivers) {
            HashMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> map = mReceivers.get(context);
            LoadedApk.ReceiverDispatcher rd = null;
            if (map != null) {
                rd = map.get(r);
                if (rd != null) {
                    map.remove(r);
                    if (map.size() == 0) {
                        mReceivers.remove(context);
                    }
                    if (r.getDebugUnregister()) {
                        HashMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> holder
                                = mUnregisteredReceivers.get(context);
                        if (holder == null) {
                            holder = new HashMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher>();
                            mUnregisteredReceivers.put(context, holder);
                        }
                        RuntimeException ex = new IllegalArgumentException(
                                "Originally unregistered here:");
                        ex.fillInStackTrace();
                        rd.setUnregisterLocation(ex);
                        holder.put(r, rd);
                    }
                    return rd.getIIntentReceiver();
                }
            }
            HashMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> holder
                    = mUnregisteredReceivers.get(context);
            if (holder != null) {
                rd = holder.get(r);
                if (rd != null) {
                    RuntimeException ex = rd.getUnregisterLocation();
                    throw new IllegalArgumentException(
                            "Unregistering Receiver " + r
                            + " that was already unregistered", ex);
                }
            }
            if (context == null) {
                throw new IllegalStateException("Unbinding Receiver " + r
                        + " from Context that is no longer in use: " + context);
            } else {
                throw new IllegalArgumentException("Receiver not registered: " + r);
            }

        }
    }

    static final class ReceiverDispatcher {

        final static class InnerReceiver extends IIntentReceiver.Stub {
            final WeakReference<LoadedApk.ReceiverDispatcher> mDispatcher;
            final LoadedApk.ReceiverDispatcher mStrongRef;

            InnerReceiver(LoadedApk.ReceiverDispatcher rd, boolean strong) {
                mDispatcher = new WeakReference<LoadedApk.ReceiverDispatcher>(rd);
                mStrongRef = strong ? rd : null;
            }
            public void performReceive(Intent intent, int resultCode,
                    String data, Bundle extras, boolean ordered, boolean sticky) {
                LoadedApk.ReceiverDispatcher rd = mDispatcher.get();
                if (ActivityThread.DEBUG_BROADCAST) {
                    int seq = intent.getIntExtra("seq", -1);
                    Slog.i(ActivityThread.TAG, "Receiving broadcast " + intent.getAction() + " seq=" + seq
                            + " to " + (rd != null ? rd.mReceiver : null));
                }
                if (rd != null) {
                    rd.performReceive(intent, resultCode, data, extras,
                            ordered, sticky);
                } else {
                    // The activity manager dispatched a broadcast to a registered
                    // receiver in this process, but before it could be delivered the
                    // receiver was unregistered.  Acknowledge the broadcast on its
                    // behalf so that the system's broadcast sequence can continue.
                    if (ActivityThread.DEBUG_BROADCAST) Slog.i(ActivityThread.TAG,
                            "Finishing broadcast to unregistered receiver");
                    IActivityManager mgr = ActivityManagerNative.getDefault();
                    try {
                        mgr.finishReceiver(this, resultCode, data, extras, false);
                    } catch (RemoteException e) {
                        Slog.w(ActivityThread.TAG, "Couldn't finish broadcast to unregistered receiver");
                    }
                }
            }
        }

        final IIntentReceiver.Stub mIIntentReceiver;
        final BroadcastReceiver mReceiver;
        final Context mContext;
        final Handler mActivityThread;
        final Instrumentation mInstrumentation;
        final boolean mRegistered;
        final IntentReceiverLeaked mLocation;
        RuntimeException mUnregisterLocation;

        final class Args implements Runnable {
            private Intent mCurIntent;
            private int mCurCode;
            private String mCurData;
            private Bundle mCurMap;
            private boolean mCurOrdered;
            private boolean mCurSticky;

            public void run() {
                BroadcastReceiver receiver = mReceiver;
                if (ActivityThread.DEBUG_BROADCAST) {
                    int seq = mCurIntent.getIntExtra("seq", -1);
                    Slog.i(ActivityThread.TAG, "Dispatching broadcast " + mCurIntent.getAction()
                            + " seq=" + seq + " to " + mReceiver);
                    Slog.i(ActivityThread.TAG, "  mRegistered=" + mRegistered
                            + " mCurOrdered=" + mCurOrdered);
                }
                
                IActivityManager mgr = ActivityManagerNative.getDefault();
                Intent intent = mCurIntent;
                mCurIntent = null;
                
                if (receiver == null) {
                    if (mRegistered && mCurOrdered) {
                        try {
                            if (ActivityThread.DEBUG_BROADCAST) Slog.i(ActivityThread.TAG,
                                    "Finishing null broadcast to " + mReceiver);
                            mgr.finishReceiver(mIIntentReceiver,
                                    mCurCode, mCurData, mCurMap, false);
                        } catch (RemoteException ex) {
                        }
                    }
                    return;
                }

                try {
                    ClassLoader cl =  mReceiver.getClass().getClassLoader();
                    intent.setExtrasClassLoader(cl);
                    if (mCurMap != null) {
                        mCurMap.setClassLoader(cl);
                    }
                    receiver.setOrderedHint(true);
                    receiver.setResult(mCurCode, mCurData, mCurMap);
                    receiver.clearAbortBroadcast();
                    receiver.setOrderedHint(mCurOrdered);
                    receiver.setInitialStickyHint(mCurSticky);
                    receiver.onReceive(mContext, intent);
                } catch (Exception e) {
                    if (mRegistered && mCurOrdered) {
                        try {
                            if (ActivityThread.DEBUG_BROADCAST) Slog.i(ActivityThread.TAG,
                                    "Finishing failed broadcast to " + mReceiver);
                            mgr.finishReceiver(mIIntentReceiver,
                                    mCurCode, mCurData, mCurMap, false);
                        } catch (RemoteException ex) {
                        }
                    }
                    if (mInstrumentation == null ||
                            !mInstrumentation.onException(mReceiver, e)) {
                        throw new RuntimeException(
                            "Error receiving broadcast " + intent
                            + " in " + mReceiver, e);
                    }
                }
                if (mRegistered && mCurOrdered) {
                    try {
                        if (ActivityThread.DEBUG_BROADCAST) Slog.i(ActivityThread.TAG,
                                "Finishing broadcast to " + mReceiver);
                        mgr.finishReceiver(mIIntentReceiver,
                                receiver.getResultCode(),
                                receiver.getResultData(),
                                receiver.getResultExtras(false),
                                receiver.getAbortBroadcast());
                    } catch (RemoteException ex) {
                    }
                }
            }
        }

        ReceiverDispatcher(BroadcastReceiver receiver, Context context,
                Handler activityThread, Instrumentation instrumentation,
                boolean registered) {
            if (activityThread == null) {
                throw new NullPointerException("Handler must not be null");
            }

            mIIntentReceiver = new InnerReceiver(this, !registered);
            mReceiver = receiver;
            mContext = context;
            mActivityThread = activityThread;
            mInstrumentation = instrumentation;
            mRegistered = registered;
            mLocation = new IntentReceiverLeaked(null);
            mLocation.fillInStackTrace();
        }

        void validate(Context context, Handler activityThread) {
            if (mContext != context) {
                throw new IllegalStateException(
                    "Receiver " + mReceiver +
                    " registered with differing Context (was " +
                    mContext + " now " + context + ")");
            }
            if (mActivityThread != activityThread) {
                throw new IllegalStateException(
                    "Receiver " + mReceiver +
                    " registered with differing handler (was " +
                    mActivityThread + " now " + activityThread + ")");
            }
        }

        IntentReceiverLeaked getLocation() {
            return mLocation;
        }

        BroadcastReceiver getIntentReceiver() {
            return mReceiver;
        }

        IIntentReceiver getIIntentReceiver() {
            return mIIntentReceiver;
        }

        void setUnregisterLocation(RuntimeException ex) {
            mUnregisterLocation = ex;
        }

        RuntimeException getUnregisterLocation() {
            return mUnregisterLocation;
        }

        public void performReceive(Intent intent, int resultCode,
                String data, Bundle extras, boolean ordered, boolean sticky) {
            if (ActivityThread.DEBUG_BROADCAST) {
                int seq = intent.getIntExtra("seq", -1);
                Slog.i(ActivityThread.TAG, "Enqueueing broadcast " + intent.getAction() + " seq=" + seq
                        + " to " + mReceiver);
            }
            Args args = new Args();
            args.mCurIntent = intent;
            args.mCurCode = resultCode;
            args.mCurData = data;
            args.mCurMap = extras;
            args.mCurOrdered = ordered;
            args.mCurSticky = sticky;
            if (!mActivityThread.post(args)) {
                if (mRegistered && ordered) {
                    IActivityManager mgr = ActivityManagerNative.getDefault();
                    try {
                        if (ActivityThread.DEBUG_BROADCAST) Slog.i(ActivityThread.TAG,
                                "Finishing sync broadcast to " + mReceiver);
                        mgr.finishReceiver(mIIntentReceiver, args.mCurCode,
                                args.mCurData, args.mCurMap, false);
                    } catch (RemoteException ex) {
                    }
                }
            }
        }

    }

    public final IServiceConnection getServiceDispatcher(ServiceConnection c,
            Context context, Handler handler, int flags) {
        synchronized (mServices) {
            LoadedApk.ServiceDispatcher sd = null;
            HashMap<ServiceConnection, LoadedApk.ServiceDispatcher> map = mServices.get(context);
            if (map != null) {
                sd = map.get(c);
            }
            if (sd == null) {
                sd = new ServiceDispatcher(c, context, handler, flags);
                if (map == null) {
                    map = new HashMap<ServiceConnection, LoadedApk.ServiceDispatcher>();
                    mServices.put(context, map);
                }
                map.put(c, sd);
            } else {
                sd.validate(context, handler);
            }
            return sd.getIServiceConnection();
        }
    }

    public final IServiceConnection forgetServiceDispatcher(Context context,
            ServiceConnection c) {
        synchronized (mServices) {
            HashMap<ServiceConnection, LoadedApk.ServiceDispatcher> map
                    = mServices.get(context);
            LoadedApk.ServiceDispatcher sd = null;
            if (map != null) {
                sd = map.get(c);
                if (sd != null) {
                    map.remove(c);
                    sd.doForget();
                    if (map.size() == 0) {
                        mServices.remove(context);
                    }
                    if ((sd.getFlags()&Context.BIND_DEBUG_UNBIND) != 0) {
                        HashMap<ServiceConnection, LoadedApk.ServiceDispatcher> holder
                                = mUnboundServices.get(context);
                        if (holder == null) {
                            holder = new HashMap<ServiceConnection, LoadedApk.ServiceDispatcher>();
                            mUnboundServices.put(context, holder);
                        }
                        RuntimeException ex = new IllegalArgumentException(
                                "Originally unbound here:");
                        ex.fillInStackTrace();
                        sd.setUnbindLocation(ex);
                        holder.put(c, sd);
                    }
                    return sd.getIServiceConnection();
                }
            }
            HashMap<ServiceConnection, LoadedApk.ServiceDispatcher> holder
                    = mUnboundServices.get(context);
            if (holder != null) {
                sd = holder.get(c);
                if (sd != null) {
                    RuntimeException ex = sd.getUnbindLocation();
                    throw new IllegalArgumentException(
                            "Unbinding Service " + c
                            + " that was already unbound", ex);
                }
            }
            if (context == null) {
                throw new IllegalStateException("Unbinding Service " + c
                        + " from Context that is no longer in use: " + context);
            } else {
                throw new IllegalArgumentException("Service not registered: " + c);
            }
        }
    }

    static final class ServiceDispatcher {
        private final ServiceDispatcher.InnerConnection mIServiceConnection;
        private final ServiceConnection mConnection;
        private final Context mContext;
        private final Handler mActivityThread;
        private final ServiceConnectionLeaked mLocation;
        private final int mFlags;

        private RuntimeException mUnbindLocation;

        private boolean mDied;

        private static class ConnectionInfo {
            IBinder binder;
            IBinder.DeathRecipient deathMonitor;
        }

        private static class InnerConnection extends IServiceConnection.Stub {
            final WeakReference<LoadedApk.ServiceDispatcher> mDispatcher;

            InnerConnection(LoadedApk.ServiceDispatcher sd) {
                mDispatcher = new WeakReference<LoadedApk.ServiceDispatcher>(sd);
            }

            public void connected(ComponentName name, IBinder service) throws RemoteException {
                LoadedApk.ServiceDispatcher sd = mDispatcher.get();
                if (sd != null) {
                    sd.connected(name, service);
                }
            }
        }

        private final HashMap<ComponentName, ServiceDispatcher.ConnectionInfo> mActiveConnections
            = new HashMap<ComponentName, ServiceDispatcher.ConnectionInfo>();

        ServiceDispatcher(ServiceConnection conn,
                Context context, Handler activityThread, int flags) {
            mIServiceConnection = new InnerConnection(this);
            mConnection = conn;
            mContext = context;
            mActivityThread = activityThread;
            mLocation = new ServiceConnectionLeaked(null);
            mLocation.fillInStackTrace();
            mFlags = flags;
        }

        void validate(Context context, Handler activityThread) {
            if (mContext != context) {
                throw new RuntimeException(
                    "ServiceConnection " + mConnection +
                    " registered with differing Context (was " +
                    mContext + " now " + context + ")");
            }
            if (mActivityThread != activityThread) {
                throw new RuntimeException(
                    "ServiceConnection " + mConnection +
                    " registered with differing handler (was " +
                    mActivityThread + " now " + activityThread + ")");
            }
        }

        void doForget() {
            synchronized(this) {
                Iterator<ServiceDispatcher.ConnectionInfo> it = mActiveConnections.values().iterator();
                while (it.hasNext()) {
                    ServiceDispatcher.ConnectionInfo ci = it.next();
                    ci.binder.unlinkToDeath(ci.deathMonitor, 0);
                }
                mActiveConnections.clear();
            }
        }

        ServiceConnectionLeaked getLocation() {
            return mLocation;
        }

        ServiceConnection getServiceConnection() {
            return mConnection;
        }

        IServiceConnection getIServiceConnection() {
            return mIServiceConnection;
        }

        int getFlags() {
            return mFlags;
        }

        void setUnbindLocation(RuntimeException ex) {
            mUnbindLocation = ex;
        }

        RuntimeException getUnbindLocation() {
            return mUnbindLocation;
        }

        public void connected(ComponentName name, IBinder service) {
            if (mActivityThread != null) {
                mActivityThread.post(new RunConnection(name, service, 0));
            } else {
                doConnected(name, service);
            }
        }

        public void death(ComponentName name, IBinder service) {
            ServiceDispatcher.ConnectionInfo old;

            synchronized (this) {
                mDied = true;
                old = mActiveConnections.remove(name);
                if (old == null || old.binder != service) {
                    // Death for someone different than who we last
                    // reported...  just ignore it.
                    return;
                }
                old.binder.unlinkToDeath(old.deathMonitor, 0);
            }

            if (mActivityThread != null) {
                mActivityThread.post(new RunConnection(name, service, 1));
            } else {
                doDeath(name, service);
            }
        }

        public void doConnected(ComponentName name, IBinder service) {
            ServiceDispatcher.ConnectionInfo old;
            ServiceDispatcher.ConnectionInfo info;

            synchronized (this) {
                old = mActiveConnections.get(name);
                if (old != null && old.binder == service) {
                    // Huh, already have this one.  Oh well!
                    return;
                }

                if (service != null) {
                    // A new service is being connected... set it all up.
                    mDied = false;
                    info = new ConnectionInfo();
                    info.binder = service;
                    info.deathMonitor = new DeathMonitor(name, service);
                    try {
                        service.linkToDeath(info.deathMonitor, 0);
                        mActiveConnections.put(name, info);
                    } catch (RemoteException e) {
                        // This service was dead before we got it...  just
                        // don't do anything with it.
                        mActiveConnections.remove(name);
                        return;
                    }

                } else {
                    // The named service is being disconnected... clean up.
                    mActiveConnections.remove(name);
                }

                if (old != null) {
                    old.binder.unlinkToDeath(old.deathMonitor, 0);
                }
            }

            // If there was an old service, it is not disconnected.
            if (old != null) {
                mConnection.onServiceDisconnected(name);
            }
            // If there is a new service, it is now connected.
            if (service != null) {
                mConnection.onServiceConnected(name, service);
            }
        }

        public void doDeath(ComponentName name, IBinder service) {
            mConnection.onServiceDisconnected(name);
        }

        private final class RunConnection implements Runnable {
            RunConnection(ComponentName name, IBinder service, int command) {
                mName = name;
                mService = service;
                mCommand = command;
            }

            public void run() {
                if (mCommand == 0) {
                    doConnected(mName, mService);
                } else if (mCommand == 1) {
                    doDeath(mName, mService);
                }
            }

            final ComponentName mName;
            final IBinder mService;
            final int mCommand;
        }

        private final class DeathMonitor implements IBinder.DeathRecipient
        {
            DeathMonitor(ComponentName name, IBinder service) {
                mName = name;
                mService = service;
            }

            public void binderDied() {
                death(mName, mService);
            }

            final ComponentName mName;
            final IBinder mService;
        }
    }
}
