// YunX HTTP launcher: start the bundled JVM without a desktop window or splash.
using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Forms;

namespace YunXDesktop
{
    static class Launcher
    {
        static string mainClass;
        static readonly List<string> javaOpts = new List<string>();
        static readonly List<string> classpath = new List<string>();

        // ---- JNI 常量与函数表下标（x64 标准布局）----
        const int JNI_VERSION_1_8 = 0x00010008;
        const int JNI_OK = 0;
        // JNINativeInterface_（JNIEnv 函数表）下标
        const int JNI_FindClass = 6;
        const int JNI_ExceptionOccurred = 15;
        const int JNI_ExceptionDescribe = 16;
        const int JNI_ExceptionClear = 17;
        const int JNI_GetStaticMethodID = 113;
        const int JNI_CallStaticVoidMethodA = 143;
        const int JNI_NewString = 163;
        const int JNI_NewObjectArray = 172;
        const int JNI_SetObjectArrayElement = 174;
        // JNIInvokeInterface_（JavaVM 函数表）下标
        const int VM_DestroyJavaVM = 3;

        [STAThread]
        static int Main(string[] args)
        {
            try { SetProcessDPIAware(); } catch { }
            Application.EnableVisualStyles();

            return RunJava(args);
        }

        static int RunJava(string[] args)
        {
            // 所有非托管内存记录在此，函数结束统一释放
            var allocations = new List<IntPtr>();
            try
            {
                // app-image 根目录 = 本 exe 所在目录（<root>\app、<root>\runtime）
                string root = AppDomain.CurrentDomain.BaseDirectory;

                string appDir = Path.Combine(root, "app");
                string runtimeBin = Path.Combine(root, "runtime", "bin");
                string jvmDll = Path.Combine(runtimeBin, "server", "jvm.dll");
                string cfgPath = Path.Combine(appDir, "YunX-Desktop.cfg");

                if (!File.Exists(jvmDll))
                {
                    Fail("未找到 Java 运行时：\n" + jvmDll);
                    return 1;
                }
                if (!File.Exists(cfgPath))
                {
                    Fail("未找到启动配置：\n" + cfgPath);
                    return 1;
                }

                ParseCfg(cfgPath);
                if (mainClass == null)
                {
                    Fail("启动配置中未指定主类（app.mainclass）");
                    return 1;
                }

                // 等效 jpackage 环境补充：原生库搜索路径（Skiko / JNA / SQLite 等）
                javaOpts.Add("-Djava.library.path=" + appDir);
                javaOpts.Add("-Dskiko.library.path=" + appDir);
                // JNI 方式没有命令行 -cp，classpath 通过系统属性传入（等价 jpackage 的 -cp）
                javaOpts.Insert(0, "-Djava.class.path=" + string.Join(";", classpath.ToArray()));

                // 闪屏模式：java 侧窗口全透明启动、内容就绪后自行渐入（与闪屏淡出交叉）

                // 工作目录与 java.exe 启动保持一致（<root>）
                Directory.SetCurrentDirectory(root);

                // 让 jvm.dll 的同目录依赖（java.dll / net.dll / msvcp140.dll ...）能在 runtime\bin 下解析
                SetDllDirectory(runtimeBin);

                IntPtr hJvm = LoadLibrary(jvmDll);
                if (hJvm == IntPtr.Zero)
                {
                    Fail("无法加载 jvm.dll（Win32 错误 " + Marshal.GetLastWin32Error() + "）：\n" + jvmDll);
                    return 1;
                }

                IntPtr pCreate = GetProcAddress(hJvm, "JNI_CreateJavaVM");
                IntPtr pGetDefault = GetProcAddress(hJvm, "JNI_GetDefaultJavaVMInitArgs");
                if (pCreate == IntPtr.Zero || pGetDefault == IntPtr.Zero)
                {
                    Fail("jvm.dll 缺少 JNI 调用接口（JNI_CreateJavaVM / JNI_GetDefaultJavaVMInitArgs）");
                    return 1;
                }
                var createVm = (JNI_CreateJavaVMDelegate)Marshal.GetDelegateForFunctionPointer(
                    pCreate, typeof(JNI_CreateJavaVMDelegate));
                var getDefaultArgs = (JNI_GetDefaultJavaVMInitArgsDelegate)Marshal.GetDelegateForFunctionPointer(
                    pGetDefault, typeof(JNI_GetDefaultJavaVMInitArgsDelegate));

                // ---- 组装 JavaVMInitArgs ----
                // x64 布局：version@0(4) nOptions@4(4) options@8(ptr) ignoreUnrecognized@16(jboolean) → 24 字节
                const int ARGS_SIZE = 24;
                IntPtr argsPtr = Alloc(allocations, ARGS_SIZE);
                for (int i = 0; i < ARGS_SIZE; i += 8) Marshal.WriteInt64(argsPtr, i, 0); // AllocHGlobal 不清零
                Marshal.WriteInt32(argsPtr, 0, JNI_VERSION_1_8);
                getDefaultArgs(argsPtr);                    // 取默认参数（版本/默认选项），失败也继续
                Marshal.WriteInt32(argsPtr, 0, JNI_VERSION_1_8);

                int n = javaOpts.Count;
                // JavaVMOption：optionString@0(ptr) extraInfo@8(ptr) → 16 字节
                IntPtr optionsPtr = Alloc(allocations, Math.Max(1, n) * 16);
                for (int i = 0; i < n; i++)
                {
                    // optionString 用 ANSI：与 JVM 在 Windows 上使用的原生路径编码（CP_ACP）一致
                    IntPtr s = Marshal.StringToHGlobalAnsi(javaOpts[i]);
                    allocations.Add(s);
                    Marshal.WriteIntPtr(optionsPtr, i * 16, s);
                    Marshal.WriteIntPtr(optionsPtr, i * 16 + 8, IntPtr.Zero);
                }
                Marshal.WriteInt32(argsPtr, 4, n);
                Marshal.WriteIntPtr(argsPtr, 8, optionsPtr);
                Marshal.WriteInt64(argsPtr, 16, 1);         // ignoreUnrecognized = JNI_TRUE

                // ---- 在本进程内创建 JVM ----
                IntPtr vmPtr = Alloc(allocations, IntPtr.Size);
                IntPtr envPtr = Alloc(allocations, IntPtr.Size);
                int rc = createVm(vmPtr, envPtr, argsPtr);
                if (rc != JNI_OK)
                {
                    Fail("创建 Java 虚拟机失败（JNI 错误码 " + rc + "）");
                    return 1;
                }
                IntPtr vm = Marshal.ReadIntPtr(vmPtr);
                IntPtr env = Marshal.ReadIntPtr(envPtr);

                // ---- 读取 JNIEnv 函数表，绑定要用到的 JNI 函数 ----
                IntPtr envTable = Marshal.ReadIntPtr(env);
                var findClass = Fn<FindClassDelegate>(envTable, JNI_FindClass);
                var getStaticMethodID = Fn<GetStaticMethodIDDelegate>(envTable, JNI_GetStaticMethodID);
                var callStaticVoidMethodA = Fn<CallStaticVoidMethodADelegate>(envTable, JNI_CallStaticVoidMethodA);
                var newString = Fn<NewStringDelegate>(envTable, JNI_NewString);
                var newObjectArray = Fn<NewObjectArrayDelegate>(envTable, JNI_NewObjectArray);
                var setObjectArrayElement = Fn<SetObjectArrayElementDelegate>(envTable, JNI_SetObjectArrayElement);
                var exceptionOccurred = Fn<ExceptionOccurredDelegate>(envTable, JNI_ExceptionOccurred);
                var exceptionDescribe = Fn<ExceptionDescribeDelegate>(envTable, JNI_ExceptionDescribe);
                var exceptionClear = Fn<ExceptionClearDelegate>(envTable, JNI_ExceptionClear);

                IntPtr mainKtClass = findClass(env, mainClass.Replace('.', '/'));
                if (mainKtClass == IntPtr.Zero)
                {
                    exceptionDescribe(env); exceptionClear(env);
                    Fail("未找到主类：" + mainClass);
                    return 1;
                }
                IntPtr mainMid = getStaticMethodID(env, mainKtClass, "main", "([Ljava/lang/String;)V");
                if (mainMid == IntPtr.Zero)
                {
                    exceptionDescribe(env); exceptionClear(env);
                    Fail("主类缺少 main(String[]) 方法：" + mainClass);
                    return 1;
                }

                // ---- 构造 String[] 实参：命令行参数透传到 main(args) ----
                IntPtr stringClass = findClass(env, "java/lang/String");
                IntPtr argArray = newObjectArray(env, args.Length, stringClass, IntPtr.Zero);
                for (int i = 0; i < args.Length; i++)
                {
                    // 用 NewString(jchar*) 传 UTF-16，天然支持中文等非 ASCII 参数
                    char[] cs = args[i].ToCharArray();
                    GCHandle pinned = GCHandle.Alloc(cs, GCHandleType.Pinned);
                    IntPtr js;
                    try { js = newString(env, pinned.AddrOfPinnedObject(), cs.Length); }
                    finally { pinned.Free(); }
                    setObjectArrayElement(env, argArray, i, js);
                }
                // jvalue 联合体（x64 为 8 字节）：仅一个变参槽，放 String[]
                IntPtr jvaluePtr = Alloc(allocations, 8);
                Marshal.WriteIntPtr(jvaluePtr, 0, argArray);

                // ---- 调用 com.yunx.app.MainKt.main(String[])（阻塞直到应用退出）----
                callStaticVoidMethodA(env, mainKtClass, mainMid, jvaluePtr);

                int code = 0;
                IntPtr pending = exceptionOccurred(env);
                if (pending != IntPtr.Zero)
                {
                    exceptionDescribe(env);   // 打印到 stderr
                    exceptionClear(env);
                    code = 1;
                }

                // DestroyJavaVM 阻塞至所有非守护线程结束，保证进程生命周期正确
                IntPtr vmTable = Marshal.ReadIntPtr(vm);
                var destroyVm = Fn<DestroyJavaVMDelegate>(vmTable, VM_DestroyJavaVM);
                destroyVm(vm);

                if (code != 0)
                {
                    Fail("HTTP 服务启动失败，请检查 YUNX_HTTP_TOKEN、端口和数据目录。错误代码：" + code);
                }
                return code;
            }
            catch (Exception ex)
            {
                Fail("启动失败：\n" + ex.Message);
                return 1;
            }
            finally
            {
                foreach (IntPtr p in allocations)
                {
                    try { Marshal.FreeHGlobal(p); } catch { }
                }
            }
        }

        static void ParseCfg(string cfgPath)
        {
            foreach (string raw in File.ReadAllLines(cfgPath, Encoding.UTF8))
            {
                string line = raw.Trim();
                if (line.Length == 0 || line.StartsWith("["))
                    continue;
                int eq = line.IndexOf('=');
                if (eq <= 0)
                    continue;
                string key = line.Substring(0, eq).Trim();
                string val = line.Substring(eq + 1).Trim();
                if (key.Equals("app.mainclass", StringComparison.OrdinalIgnoreCase))
                    mainClass = val;
                else if (key.Equals("app.classpath", StringComparison.OrdinalIgnoreCase))
                    classpath.Add(val.Replace("$APPDIR",
                        Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "app")));
                else if (key.Equals("java-options", StringComparison.OrdinalIgnoreCase))
                {
                    if (val.Length >= 2 && val.StartsWith("\"") && val.EndsWith("\""))
                        val = val.Substring(1, val.Length - 2);
                    javaOpts.Add(val);
                }
            }
        }

        // ---- 非托管内存 / JNI 绑定小工具 ----
        static IntPtr Alloc(List<IntPtr> keep, int size)
        {
            IntPtr p = Marshal.AllocHGlobal(size);
            keep.Add(p);
            return p;
        }

        /// 从 JNI 函数表取第 index 个函数并转成委托
        static T Fn<T>(IntPtr table, int index)
        {
            IntPtr p = Marshal.ReadIntPtr(table, index * IntPtr.Size);
            return (T)(object)Marshal.GetDelegateForFunctionPointer(p, typeof(T));
        }

        static void Fail(string msg)
        {
            try
            {
                MessageBox.Show(msg, "云析 YunX-Desktop", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            catch { }
        }

        // ---- JNI 委托（JNICALL = stdcall；x64 下与默认调用约定相同）----
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate int JNI_CreateJavaVMDelegate(IntPtr pvm, IntPtr penv, IntPtr args);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate int JNI_GetDefaultJavaVMInitArgsDelegate(IntPtr args);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate int DestroyJavaVMDelegate(IntPtr vm);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate IntPtr FindClassDelegate(IntPtr env, [MarshalAs(UnmanagedType.LPStr)] string name);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate IntPtr GetStaticMethodIDDelegate(IntPtr env, IntPtr clazz,
            [MarshalAs(UnmanagedType.LPStr)] string name, [MarshalAs(UnmanagedType.LPStr)] string sig);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate void CallStaticVoidMethodADelegate(IntPtr env, IntPtr clazz, IntPtr methodID, IntPtr args);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate IntPtr NewStringDelegate(IntPtr env, IntPtr unicode, int len);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate IntPtr NewObjectArrayDelegate(IntPtr env, int len, IntPtr elementClass, IntPtr initial);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate void SetObjectArrayElementDelegate(IntPtr env, IntPtr array, int index, IntPtr val);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate IntPtr ExceptionOccurredDelegate(IntPtr env);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate void ExceptionDescribeDelegate(IntPtr env);
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        delegate void ExceptionClearDelegate(IntPtr env);

        // ---- Win32 ----

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern IntPtr LoadLibrary(string lpFileName);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern bool SetDllDirectory(string lpPathName);
        [DllImport("kernel32.dll", CharSet = CharSet.Ansi, SetLastError = true, BestFitMapping = false)]
        static extern IntPtr GetProcAddress(IntPtr hModule, string lpProcName);
        [DllImport("user32.dll")] static extern bool SetProcessDPIAware();
    }

}
