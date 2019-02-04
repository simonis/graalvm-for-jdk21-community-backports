/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso.vm;

import static com.oracle.truffle.espresso.jni.JniVersion.JNI_VERSION_1_1;
import static com.oracle.truffle.espresso.jni.JniVersion.JNI_VERSION_1_2;
import static com.oracle.truffle.espresso.jni.JniVersion.JNI_VERSION_1_4;
import static com.oracle.truffle.espresso.jni.JniVersion.JNI_VERSION_1_6;
import static com.oracle.truffle.espresso.jni.JniVersion.JNI_VERSION_1_8;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.graalvm.options.OptionValues;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstanceVisitor;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.espresso.EspressoLanguage;
import com.oracle.truffle.espresso.EspressoOptions;
import com.oracle.truffle.espresso.descriptors.Types;
import com.oracle.truffle.espresso.impl.ByteString;
import com.oracle.truffle.espresso.impl.ByteString.Name;
import com.oracle.truffle.espresso.impl.ByteString.Type;
import com.oracle.truffle.espresso.impl.ContextAccess;
import com.oracle.truffle.espresso.impl.Klass;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.jni.Callback;
import com.oracle.truffle.espresso.jni.JniEnv;
import com.oracle.truffle.espresso.jni.JniImpl;
import com.oracle.truffle.espresso.jni.NFIType;
import com.oracle.truffle.espresso.jni.NativeEnv;
import com.oracle.truffle.espresso.jni.NativeLibrary;
import com.oracle.truffle.espresso.meta.EspressoError;
import com.oracle.truffle.espresso.meta.JavaKind;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.meta.MetaUtil;
import com.oracle.truffle.espresso.nodes.EspressoRootNode;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.EspressoException;
import com.oracle.truffle.espresso.runtime.EspressoExitException;
import com.oracle.truffle.espresso.runtime.EspressoProperties;
import com.oracle.truffle.espresso.runtime.StaticObject;
import com.oracle.truffle.espresso.runtime.StaticObjectArray;
import com.oracle.truffle.espresso.runtime.StaticObjectClass;
import com.oracle.truffle.espresso.runtime.StaticObjectImpl;
import com.oracle.truffle.espresso.substitutions.Host;
import com.oracle.truffle.espresso.substitutions.SuppressFBWarnings;
import com.oracle.truffle.nfi.types.NativeSimpleType;

/**
 * Espresso implementation of the VM interface (libjvm).
 */
public final class VM extends NativeEnv implements ContextAccess {

    private final TruffleObject initializeMokapotContext;
    private final TruffleObject disposeMokapotContext;
    private final TruffleObject getJavaVM;

    private final JniEnv jniEnv;

    private long vmPtr;

    // mokapot.dll (Windows) or libmokapot.so (Unixes) is the Espresso implementation of the VM
    // interface (libjvm).
    // Espresso loads all shared libraries in a private namespace (e.g. using dlmopen on Linux).
    // libmokapot must be loaded strictly before any other library in the private namespace to
    // avoid linking with HotSpot libjvm, then libjava is loaded and further system libraries,
    // libzip, libnet, libnio ...
    private final TruffleObject mokapotLibrary;

    // libjava must be loaded after mokapot.
    private final TruffleObject javaLibrary;

    public TruffleObject getJavaLibrary() {
        return javaLibrary;
    }

    private VM(JniEnv jniEnv) {
        this.jniEnv = jniEnv;
        try {
            EspressoProperties props = getContext().getVmProperties();

            List<String> libjavaSearchPaths = new ArrayList<>(Arrays.asList(props.getBootLibraryPath().split(File.pathSeparator)));
            libjavaSearchPaths.addAll(Arrays.asList(props.getJavaLibraryPath().split(File.pathSeparator)));

            mokapotLibrary = loadLibrary(props.getEspressoLibraryPath().split(File.pathSeparator), "mokapot");

            assert mokapotLibrary != null;
            javaLibrary = loadLibrary(libjavaSearchPaths.toArray(new String[0]), "java");

            initializeMokapotContext = NativeLibrary.lookupAndBind(mokapotLibrary,
                            "initializeMokapotContext", "(env, sint64, (string): pointer): sint64");

            disposeMokapotContext = NativeLibrary.lookupAndBind(mokapotLibrary,
                            "disposeMokapotContext",
                            "(env, sint64): void");

            getJavaVM = NativeLibrary.lookupAndBind(mokapotLibrary,
                            "getJavaVM",
                            "(env): sint64");

            Callback lookupVmImplCallback = Callback.wrapInstanceMethod(this, "lookupVmImpl", String.class);
            this.vmPtr = (long) ForeignAccess.sendExecute(Message.EXECUTE.createNode(), initializeMokapotContext, jniEnv.getNativePointer(), lookupVmImplCallback);

            assert this.vmPtr != 0;

        } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException | UnknownIdentifierException e) {
            throw EspressoError.shouldNotReachHere(e);
        }
    }

    @Override
    public final EspressoContext getContext() {
        return jniEnv.getContext();
    }

    public long getJavaVM() {
        try {
            return (long) ForeignAccess.sendExecute(Message.EXECUTE.createNode(), getJavaVM);
        } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
            throw EspressoError.shouldNotReachHere("getJavaVM failed");
        }
    }

    private static Map<String, java.lang.reflect.Method> buildVmMethods() {
        Map<String, java.lang.reflect.Method> map = new HashMap<>();
        java.lang.reflect.Method[] declaredMethods = VM.class.getDeclaredMethods();
        for (java.lang.reflect.Method method : declaredMethods) {
            VmImpl jniImpl = method.getAnnotation(VmImpl.class);
            if (jniImpl != null) {
                assert !map.containsKey(method.getName()) : "VmImpl for " + method + " already exists";
                map.put(method.getName(), method);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static final Map<String, java.lang.reflect.Method> vmMethods = buildVmMethods();

    public static VM create(JniEnv jniEnv) {
        return new VM(jniEnv);
    }

    public static String vmNativeSignature(java.lang.reflect.Method method) {
        StringBuilder sb = new StringBuilder("(");

        boolean first = true;
        if (method.getAnnotation(JniImpl.class) != null) {
            sb.append(NativeSimpleType.SINT64); // Prepend JNIEnv*;
            first = false;
        }

        for (Parameter param : method.getParameters()) {
            if (!first) {
                sb.append(", ");
            } else {
                first = false;
            }

            // Override NFI type.
            NFIType nfiType = param.getAnnotatedType().getAnnotation(NFIType.class);
            if (nfiType != null) {
                sb.append(NativeSimpleType.valueOf(nfiType.value().toUpperCase()));
            } else {
                sb.append(classToType(param.getType(), false));
            }
        }
        sb.append("): ").append(classToType(method.getReturnType(), true));
        return sb.toString();
    }

    private static final int JVM_CALLER_DEPTH = -1;

    public TruffleObject lookupVmImpl(String methodName) {
        java.lang.reflect.Method m = vmMethods.get(methodName);
        try {
            // Dummy placeholder for unimplemented/unknown methods.
            if (m == null) {
                // System.err.println("Fetching unknown/unimplemented VM method: " + methodName);
                return (TruffleObject) ForeignAccess.sendExecute(Message.EXECUTE.createNode(), jniEnv.dupClosureRefAndCast("(pointer): void"),
                                new Callback(1, new Callback.Function() {
                                    @Override
                                    public Object call(Object... args) {
                                        System.err.println("Calling unimplemented VM method: " + methodName);
                                        throw EspressoError.unimplemented("VM method: " + methodName);
                                    }
                                }));
            }

            String signature = vmNativeSignature(m);
            Callback target = vmMethodWrapper(m);
            return (TruffleObject) ForeignAccess.sendExecute(Message.EXECUTE.createNode(), jniEnv.dupClosureRefAndCast(signature), target);

        } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
            throw EspressoError.shouldNotReachHere(e);
        }
    }

    // region VM methods

    @VmImpl
    @JniImpl
    public long JVM_CurrentTimeMillis(@SuppressWarnings("unused") StaticObject ignored) {
        return System.currentTimeMillis();
    }

    @VmImpl
    @JniImpl
    public long JVM_NanoTime(@SuppressWarnings("unused") StaticObject ignored) {
        return System.nanoTime();
    }

    /**
     * (Identity) hash code must be respected for wrappers. The same object could be wrapped by two
     * different instances of StaticObjectWrapper. Wrappers are transparent, it's identity comes
     * from the wrapped object.
     */
    @VmImpl
    @JniImpl
    public int JVM_IHashCode(@Host(Object.class) StaticObject object) {
        return System.identityHashCode(MetaUtil.unwrap(object));
    }

    @VmImpl
    @JniImpl
    public void JVM_ArrayCopy(@SuppressWarnings("unused") Object ignored, @Host(Object.class) StaticObject src, int srcPos, @Host(Object.class) StaticObject dest, int destPos, int length) {
        try {
            if (src instanceof StaticObjectArray && dest instanceof StaticObjectArray) {
                System.arraycopy(((StaticObjectArray) src).unwrap(), srcPos, ((StaticObjectArray) dest).unwrap(), destPos, length);
            } else {
                assert src.getClass().isArray();
                assert dest.getClass().isArray();
                System.arraycopy(src, srcPos, dest, destPos, length);
            }
        } catch (Exception e) {
            throw getMeta().throwEx(e.getClass(), e.getMessage());
        }
    }

    @VmImpl
    @JniImpl
    public @Host(Object.class) StaticObject JVM_Clone(@Host(Object.class) StaticObject self) {
        if (self instanceof StaticObjectArray) {
            // For arrays.
            return ((StaticObjectArray) self).copy();
        }
        Meta meta = getMeta();
        if (!meta.Cloneable.isAssignableFrom(self.getKlass())) {
            throw meta.throwEx(java.lang.CloneNotSupportedException.class);
        }

        // Normal object just copy the fields.
        return ((StaticObjectImpl) self).copy();
    }

    public Callback vmMethodWrapper(java.lang.reflect.Method m) {
        int extraArg = (m.getAnnotation(JniImpl.class) != null) ? 1 : 0;

        return new Callback(m.getParameterCount() + extraArg, new Callback.Function() {
            @Override
            public Object call(Object... rawArgs) {

                boolean isJni = (m.getAnnotation(JniImpl.class) != null);

                Object[] args;
                if (isJni) {
                    assert (long) rawArgs[0] == jniEnv.getNativePointer() : "Calling JVM_ method " + m + " from alien JniEnv";
                    args = Arrays.copyOfRange(rawArgs, 1, rawArgs.length); // Strip JNIEnv* pointer,
                    // replace
                    // by VM (this) receiver.
                } else {
                    args = rawArgs;
                }

                Class<?>[] params = m.getParameterTypes();

                for (int i = 0; i < args.length; ++i) {
                    // FIXME(peterssen): Espresso should accept interop null objects, since it
                    // doesn't
                    // we must convert to Espresso null.
                    // FIXME(peterssen): Also, do use proper nodes.
                    if (args[i] instanceof TruffleObject) {
                        if (ForeignAccess.sendIsNull(Message.IS_NULL.createNode(), (TruffleObject) args[i])) {
                            args[i] = StaticObject.NULL;
                        }
                    } else {
                        // TruffleNFI pass booleans as byte, do the proper conversion.
                        if (params[i] == boolean.class) {
                            args[i] = ((byte) args[i]) != 0;
                        }
                    }
                }
                try {
                    // Substitute raw pointer by proper `this` reference.
                    // System.err.print("Call DEFINED method: " + m.getName() +
                    // Arrays.toString(shiftedArgs));
                    Object ret = m.invoke(VM.this, args);

                    if (ret instanceof Boolean) {
                        return (boolean) ret ? (byte) 1 : (byte) 0;
                    }

                    if (ret == null && !m.getReturnType().isPrimitive()) {
                        throw EspressoError.shouldNotReachHere("Cannot return host null, only Espresso NULL");
                    }

                    if (ret == null && m.getReturnType() == void.class) {
                        // Cannot return host null to TruffleNFI.
                        ret = StaticObject.NULL;
                    }

                    // System.err.println(" -> " + ret);

                    return ret;
                } catch (InvocationTargetException e) {
                    Throwable targetEx = e.getTargetException();
                    if (isJni) {
                        if (targetEx instanceof EspressoException) {
                            jniEnv.getThreadLocalPendingException().set(((EspressoException) targetEx).getException());
                            return defaultValue(m.getReturnType());
                        }
                    }
                    if (targetEx instanceof RuntimeException) {
                        throw (RuntimeException) targetEx;
                    }
                    // FIXME(peterssen): Handle VME exceptions back to guest.
                    throw EspressoError.shouldNotReachHere(targetEx);
                } catch (IllegalAccessException e) {
                    throw EspressoError.shouldNotReachHere(e);
                }
            }
        });
    }

    @VmImpl
    @JniImpl
    @SuppressFBWarnings(value = {"IMSE"}, justification = "Not dubious, .notifyAll is just forwarded from the guest.")
    public void JVM_MonitorNotifyAll(@Host(Object.class) StaticObject self) {
        if (EspressoOptions.RUNNING_ON_SVM) {
            return;
        }
        try {
            MetaUtil.unwrap(self).notifyAll();
        } catch (IllegalMonitorStateException e) {
            throw getMeta().throwEx(e.getClass(), e.getMessage());
        }
    }

    @VmImpl
    @JniImpl
    @SuppressFBWarnings(value = {"IMSE"}, justification = "Not dubious, .notify is just forwarded from the guest.")
    public void JVM_MonitorNotify(@Host(Object.class) StaticObject self) {
        if (EspressoOptions.RUNNING_ON_SVM) {
            return;
        }
        try {
            MetaUtil.unwrap(self).notify();
        } catch (IllegalMonitorStateException e) {
            throw getMeta().throwEx(e.getClass(), e.getMessage());
        }
    }

    @VmImpl
    @JniImpl
    @SuppressFBWarnings(value = {"IMSE"}, justification = "Not dubious, .wait is just forwarded from the guest.")
    public void JVM_MonitorWait(@Host(Object.class) StaticObject self, long timeout) {
        if (EspressoOptions.RUNNING_ON_SVM) {
            return;
        }
        try {
            MetaUtil.unwrap(self).wait(timeout);
        } catch (InterruptedException | IllegalMonitorStateException | IllegalArgumentException e) {
            throw getMeta().throwEx(e.getClass(), e.getMessage());
        }
    }

    @VmImpl
    public void JVM_Halt(int code) {
        // Runtime.getRuntime().halt(code);
        throw new EspressoExitException(code);
    }

    @VmImpl
    public boolean JVM_IsNaN(double d) {
        return Double.isNaN(d);
    }

    @VmImpl
    public boolean JVM_SupportsCX8() {
        try {
            Class<?> klass = Class.forName("java.util.concurrent.atomic.AtomicLong");
            Field field = klass.getDeclaredField("VM_SUPPORTS_LONG_CAS");
            field.setAccessible(true);
            return field.getBoolean(null);
        } catch (IllegalAccessException | NoSuchFieldException | ClassNotFoundException e) {
            throw EspressoError.shouldNotReachHere(e);
        }
    }

    @VmImpl
    @JniImpl
    // TODO(peterssen): @Type annotaion only for readability purposes.
    public @Host(String.class) StaticObject JVM_InternString(@Host(String.class) StaticObject self) {
        return getInterpreterToVM().intern(self);
    }

    // endregion VM methods

    // region JNI Invocation Interface

    @VmImpl
    public int DestroyJavaVM() {
        return JniEnv.JNI_OK;
    }

    @SuppressWarnings("unused")
    @VmImpl
    public int AttachCurrentThread(long penvPtr, long argsPtr) {
        return JniEnv.JNI_OK;
    }

    @VmImpl
    public int DetachCurrentThread() {
        return JniEnv.JNI_OK;
    }

    /**
     * <h3>jint GetEnv(JavaVM *vm, void **env, jint version);</h3>
     *
     * @param vmPtr_ The virtual machine instance from which the interface will be retrieved.
     * @param envPtr pointer to the location where the JNI interface pointer for the current thread
     *            will be placed.
     * @param version The requested JNI version.
     *
     * @returns If the current thread is not attached to the VM, sets *env to NULL, and returns
     *          JNI_EDETACHED. If the specified version is not supported, sets *env to NULL, and
     *          returns JNI_EVERSION. Otherwise, sets *env to the appropriate interface, and returns
     *          JNI_OK.
     */
    @SuppressWarnings("unused")
    @VmImpl
    public int GetEnv(long vmPtr_, long envPtr, int version) {
        // TODO(peterssen): Check the thread is attached, and that the VM pointer matches.
        LongBuffer buf = directByteBuffer(envPtr, 1, JavaKind.Long).asLongBuffer();
        buf.put(jniEnv.getNativePointer());
        return JniEnv.JNI_OK;
    }

    @SuppressWarnings("unused")
    @VmImpl
    public int AttachCurrentThreadAsDaemon(long penvPtr, long argsPtr) {
        return JniEnv.JNI_OK;
    }

    // endregion JNI Invocation Interface

    @VmImpl
    @JniImpl
    public @Host(Throwable.class) StaticObject JVM_FillInStackTrace(@Host(Throwable.class) StaticObject self, @SuppressWarnings("unused") int dummy) {
        final ArrayList<FrameInstance> frames = new ArrayList<>(32);
        Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<Object>() {
            @Override
            public Object visitFrame(FrameInstance frameInstance) {
                frames.add(frameInstance);
                return null;
            }
        });
        StaticObject backtrace = getMeta().Object.allocateInstance();
        ((StaticObjectImpl) backtrace).setHiddenField("$$frames", frames.toArray(new FrameInstance[0]));
        getMeta().Throwable_backtrace.set(self, backtrace);
        return self;
    }

    @VmImpl
    @JniImpl
    public int JVM_GetStackTraceDepth(@Host(Throwable.class) StaticObject self) {
        StaticObject backtrace = (StaticObject) getMeta().Throwable_backtrace.get(self);
        if (StaticObject.isNull(backtrace)) {
            return 0;
        }
        return ((FrameInstance[]) ((StaticObjectImpl) backtrace).getHiddenField("$$frames")).length;
    }

    @VmImpl
    @JniImpl
    public @Host(StackTraceElement.class) StaticObject JVM_GetStackTraceElement(@Host(Throwable.class) StaticObject self, int index) {
        StaticObject ste = getMeta().StackTraceElement.allocateInstance();
        StaticObject backtrace = (StaticObject) getMeta().Throwable_backtrace.get(self);
        FrameInstance[] frames = ((FrameInstance[]) ((StaticObjectImpl) backtrace).getHiddenField("$$frames"));
        FrameInstance frame = frames[index];

        EspressoRootNode rootNode = (EspressoRootNode) ((RootCallTarget) frame.getCallTarget()).getRootNode();

        getMeta().StackTraceElement_init.invokeDirect(
                        /* this */ ste,
                        /* declaringClass */ getMeta().toGuestString(rootNode.getMethod().getName()),
                        /* methodName */ getMeta().toGuestString(rootNode.getMethod().getName()),
                        /* fileName */ StaticObject.NULL,
                        /* lineNumber */ -1);

        return ste;
    }

    @VmImpl
    @JniImpl
    public int JVM_ConstantPoolGetSize(@SuppressWarnings("unused") Object unused, StaticObjectClass jcpool) {
        return jcpool.getMirror().getConstantPool().length();
    }

    @VmImpl
    @JniImpl
    public @Host(String.class) StaticObject JVM_ConstantPoolGetUTF8At(@SuppressWarnings("unused") Object unused, StaticObjectClass jcpool, int index) {
        return getMeta().toGuestString(jcpool.getMirror().getConstantPool().utf8At(index).toString());
    }

    @VmImpl
    @JniImpl
    public @Host(Class.class) StaticObject JVM_DefineClass(String name, @Host(ClassLoader.class) StaticObject loader, long bufPtr, int len,
                    @SuppressWarnings("unused") @Host(ProtectionDomain.class) Object pd) {
        // TODO(peterssen): The protection domain is unused.
        ByteBuffer buf = JniEnv.directByteBuffer(bufPtr, len, JavaKind.Byte);
        final byte[] bytes = new byte[len];
        buf.get(bytes);

        ByteString<Type> type = Types.fromConstantPoolName(ByteString.fromJavaString(name));

        StaticObjectClass klass = (StaticObjectClass) getContext().getRegistries().defineKlass(type, bytes, loader).mirror();
        return klass;
    }

    @VmImpl
    @JniImpl
    public @Host(Class.class) StaticObject JVM_DefineClassWithSource(String name, @Host(ClassLoader.class) StaticObject loader, long bufPtr, int len,
                    @Host(ProtectionDomain.class) Object pd, @SuppressWarnings("unused") String source) {
        // FIXME(peterssen): Source is ignored.
        return JVM_DefineClass(name, loader, bufPtr, len, pd);
    }

    @VmImpl
    @JniImpl
    public Object JVM_NewInstanceFromConstructor(@Host(Constructor.class) StaticObject constructor, @Host(Object[].class) StaticObject args0) {
        Klass klass = ((StaticObjectClass) getMeta().Constructor_clazz.get(constructor)).getMirror();
        klass.initialize();
        if (klass.isArray() || klass.isPrimitive() || klass.isInterface() || klass.isAbstract()) {
            throw klass.getMeta().throwEx(InstantiationException.class);
        }
        StaticObject instance = klass.allocateInstance();

        StaticObject args = StaticObject.isNull(args0) ? (StaticObject) getMeta().Object.allocateArray(0) : args0;

        StaticObject curInit = constructor;

        // Find constructor root.
        Method target = null;
        while (target == null) {
            target = (Method) ((StaticObjectImpl) curInit).getHiddenField("$$method_info");
            if (target == null) {
                curInit = (StaticObject) getMeta().Constructor_root.get(curInit);
            }
        }

        target.invokeDirect(instance, ((StaticObjectArray) args).unwrap());
        return instance;
    }

    @VmImpl
    @JniImpl
    public @Host(Class.class) StaticObject JVM_FindLoadedClass(@Host(ClassLoader.class) StaticObject loader, @Host(String.class) StaticObject name) {
        ByteString<Type> type = Types.fromJavaString(MetaUtil.toInternalName(Meta.toHostString(name)));
        Klass klass = getContext().getRegistries().findLoadedClass(type, loader);
        if (klass == null) {
            return StaticObject.NULL;
        }
        return klass.mirror();
    }

    private final ConcurrentHashMap<Long, TruffleObject> handle2Lib = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, TruffleObject> handle2Sym = new ConcurrentHashMap<>();

    // region Library support

    @VmImpl
    public long JVM_LoadLibrary(String name) {
        try {
            TruffleObject lib = NativeLibrary.loadLibrary(name);
            Field f = lib.getClass().getDeclaredField("handle");
            f.setAccessible(true);
            long handle = (long) f.get(lib);
            handle2Lib.put(handle, lib);
            return handle;
        } catch (IllegalAccessException | NoSuchFieldException e) {
            throw EspressoError.shouldNotReachHere(e);
        }
    }

    @VmImpl
    public void JVM_UnloadLibrary(@SuppressWarnings("unused") long handle) {
        // TODO(peterssen): Do unload the library.
        System.err.println("JVM_UnloadLibrary called but library was not unloaded!");
    }

    @VmImpl
    public long JVM_FindLibraryEntry(long libHandle, String name) {
        if (libHandle == 0) {
            System.err.println("JVM_FindLibraryEntry from default/global namespace (0): " + name);
            return 0L;
        }
        try {
            TruffleObject function = NativeLibrary.lookup(handle2Lib.get(libHandle), name);
            long handle = (long) ForeignAccess.sendUnbox(Message.UNBOX.createNode(), function);
            handle2Sym.put(handle, function);
            return handle;
        } catch (UnsupportedMessageException e) {
            throw EspressoError.shouldNotReachHere(e);
        } catch (UnknownIdentifierException e) {
            return 0; // not found
        }
    }

    // endregion Library support

    @VmImpl
    public boolean JVM_IsSupportedJNIVersion(int version) {
        return version == JNI_VERSION_1_1 ||
                        version == JNI_VERSION_1_2 ||
                        version == JNI_VERSION_1_4 ||
                        version == JNI_VERSION_1_6 ||
                        version == JNI_VERSION_1_8;
    }

    @VmImpl
    public int JVM_GetInterfaceVersion() {
        return JniEnv.JVM_INTERFACE_VERSION;
    }

    public void dispose() {
        assert vmPtr != 0L : "Mokapot already disposed";
        try {
            ForeignAccess.sendExecute(Message.EXECUTE.createNode(), disposeMokapotContext, vmPtr);
            this.vmPtr = 0L;
        } catch (UnsupportedTypeException | ArityException | UnsupportedMessageException e) {
            throw EspressoError.shouldNotReachHere("Cannot dispose Espresso libjvm (mokapot).");
        }
        assert vmPtr == 0L;
    }

    @VmImpl
    public long JVM_TotalMemory() {
        // TODO(peterssen): What to report here?
        return Runtime.getRuntime().totalMemory();
    }

    @VmImpl
    public void JVM_GC() {
        System.gc();
    }

    @VmImpl
    public void JVM_Exit(int code) {
        // System.exit(code);
        // Unlike Halt, runs finalizers
        throw new EspressoExitException(code);
    }

    @VmImpl
    @JniImpl
    public @Host(Properties.class) StaticObject JVM_InitProperties(@Host(Properties.class) StaticObject properties) {
        Method setProperty = properties.getKlass().lookupMethod(Name.setProperty,
                        getSignatures().makeRaw(Type.Object, Type.String, Type.String));

        OptionValues options = getContext().getEnv().getOptions();

        // Set user-defined system properties.
        for (Map.Entry<String, String> entry : options.get(EspressoOptions.Properties).entrySet()) {
            setProperty.invokeWithConversions(null, entry.getKey(), entry.getValue());
        }

        // TODO(peterssen): Use EspressoProperties to store classpath.
        EspressoError.guarantee(options.hasBeenSet(EspressoOptions.Classpath), "Classpath must be defined.");
        setProperty.invokeWithConversions(properties,"java.class.path", options.get(EspressoOptions.Classpath));

        EspressoProperties props = getContext().getVmProperties();
        setProperty.invokeWithConversions(properties, "java.home", props.getJavaHome());
        setProperty.invokeWithConversions(properties, "sun.boot.class.path", props.getBootClasspath());
        setProperty.invokeWithConversions(properties, "java.library.path", props.getJavaLibraryPath());
        setProperty.invokeWithConversions(properties, "sun.boot.library.path", props.getBootLibraryPath());
        setProperty.invokeWithConversions(properties, "java.ext.dirs", props.getExtDirs());

        return properties;
    }

    @VmImpl
    @JniImpl
    public int JVM_GetArrayLength(@Host(Object.class) StaticObject array) {
        try {
            return Array.getLength(MetaUtil.unwrap(array));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw getMeta().throwEx(e.getClass(), e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    @VmImpl
    @JniImpl
    public boolean JVM_DesiredAssertionStatus(@Host(Class.class) StaticObject unused, @Host(Class.class) StaticObject cls) {
        // TODO(peterssen): Assertions are always disabled, use the VM arguments.
        return false;
    }

    @VmImpl
    @JniImpl
    public @Host(Class.class) StaticObject JVM_GetCallerClass(int depth) {
        // TODO(peterssen): HotSpot verifies that the method is marked as @CallerSensitive.
        // Non-Espresso frames (e.g TruffleNFI) are ignored.
        // The call stack should look like this:
        // 2 : the @CallerSensitive annotated method.
        // ... : skipped non-Espresso frames.
        // 1 : getCallerClass method.
        // ... :
        // 0 : the callee.
        //
        // JVM_CALLER_DEPTH => the caller.
        int callerDepth = (depth == JVM_CALLER_DEPTH) ? 2 : depth + 1;

        final int[] depthCounter = new int[]{callerDepth};
        CallTarget caller = Truffle.getRuntime().iterateFrames(
                        new FrameInstanceVisitor<CallTarget>() {
                            @Override
                            public CallTarget visitFrame(FrameInstance frameInstance) {
                                if (frameInstance.getCallTarget() instanceof RootCallTarget) {
                                    RootCallTarget callTarget = (RootCallTarget) frameInstance.getCallTarget();
                                    RootNode rootNode = callTarget.getRootNode();
                                    if (rootNode instanceof EspressoRootNode) {
                                        if (--depthCounter[0] < 0) {
                                            return frameInstance.getCallTarget();
                                        }
                                    }
                                }
                                return null;
                            }
                        });

        RootCallTarget callTarget = (RootCallTarget) caller;
        RootNode rootNode = callTarget.getRootNode();
        if (rootNode instanceof EspressoRootNode) {
            return ((EspressoRootNode) rootNode).getMethod().getDeclaringKlass().mirror();
        }

        throw EspressoError.shouldNotReachHere();
    }

    @VmImpl
    @JniImpl
    public int JVM_GetClassAccessFlags(@Host(Class.class) StaticObject clazz) {
        Klass klass = ((StaticObjectClass) clazz).getMirror();
        return klass.getModifiers();
    }

    @VmImpl
    @JniImpl
    public @Host(Class.class) StaticObject JVM_FindClassFromBootLoader(String name) {
        Klass klass = getRegistries().loadKlassWithBootClassLoader(Types.fromJavaString(MetaUtil.toInternalName(name)));
        if (klass == null) {
            return StaticObject.NULL;
        }
        return klass.mirror();
    }

    public TruffleObject getLibrary(long handle) {
        return handle2Lib.get(handle);
    }

    public TruffleObject getFunction(long handle) {
        return handle2Sym.get(handle);
    }

    /**
     * Returns the value of the indexed component in the specified array object. The value is
     * automatically wrapped in an object if it has a primitive type.
     *
     * @param array the array
     * @param index the index
     * @returns the (possibly wrapped) value of the indexed component in the specified array
     * @exception NullPointerException If the specified object is null
     * @exception IllegalArgumentException If the specified object is not an array
     * @exception ArrayIndexOutOfBoundsException If the specified {@code index} argument is
     *                negative, or if it is greater than or equal to the length of the specified
     *                array
     */
    @VmImpl
    @JniImpl
    public @Host(Object.class) StaticObject JVM_GetArrayElement(@Host(Object.class) StaticObject array, int index) {
        if (StaticObject.isNull(array)) {
            throw getMeta().throwEx(NullPointerException.class);
        }
        if (array instanceof StaticObjectArray) {
            return getInterpreterToVM().getArrayObject(index, array);
        }
        if (!array.getClass().isArray()) {
            throw getMeta().throwEx(IllegalArgumentException.class, "Argument is not an array");
        }
        assert array.getClass().isArray() && array.getClass().getComponentType().isPrimitive();
        if (index < 0 || index >= JVM_GetArrayLength(array)) {
            throw getMeta().throwEx(ArrayIndexOutOfBoundsException.class, "index");
        }
        Object elem = Array.get(array, index);
        return guestBox(elem);
    }

    private StaticObject guestBox(Object elem) {
        if (elem instanceof Integer) {
            return (StaticObject) getMeta().Integer_valueOf.invokeDirect(null, (int) elem);
        }
        if (elem instanceof Boolean) {
            return (StaticObject) getMeta().Boolean_valueOf.invokeDirect(null, (boolean) elem);
        }
        if (elem instanceof Byte) {
            return (StaticObject) getMeta().Byte_valueOf.invokeDirect(null, (byte) elem);
        }
        if (elem instanceof Character) {
            return (StaticObject) getMeta().Character_valueOf.invokeDirect(null, (char) elem);
        }
        if (elem instanceof Short) {
            return (StaticObject) getMeta().Short_valueOf.invokeDirect(null, (short) elem);
        }
        if (elem instanceof Float) {
            return (StaticObject) getMeta().Float_valueOf.invokeDirect(null, (float) elem);
        }
        if (elem instanceof Double) {
            return (StaticObject) getMeta().Double_valueOf.invokeDirect(null, (double) elem);
        }
        if (elem instanceof Long) {
            return (StaticObject) getMeta().Long_valueOf.invokeDirect(null, (long) elem);
        }

        throw EspressoError.shouldNotReachHere("Not a boxed type " + elem);
    }
}