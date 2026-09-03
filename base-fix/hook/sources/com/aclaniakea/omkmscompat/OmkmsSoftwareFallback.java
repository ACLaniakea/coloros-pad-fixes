package com.aclaniakea.omkmscompat;

import com.aclaniakea.devicegate.DeviceGate;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Uses StdSP's own stock software implementation for the one OMK operation
 * that the Lenovo port cannot send to an OPPO TEE.
 *
 * <p>The platform property selects the hardware implementation on this port,
 * but its shared CryptoEng backend has no TA for command 0x322 (802).  StdSP
 * deliberately ships {@code tee.e_c} as the OEM fallback: it retains the
 * stock OSEC ECIES protocol and stores its wrapping secret in Android
 * Keystore with the regular lock-screen authorization policy.  Delegating
 * only {@code e_d.e_a(String, String[])} maps exactly to 0x322.  Other OMK
 * operations remain on their original path.</p>
 */
public final class OmkmsSoftwareFallback implements IXposedHookLoadPackage {
    private static final String TAG = "OmkmsSoftwareFallback";
    private static final String STDSP = "com.oplus.stdsp";
    private static final String TEE_HARDWARE = "com.oplus.omkm2x.tee.e_d";
    private static final String TEE_SOFTWARE = "com.oplus.omkm2x.tee.e_c";
    private static final String DATA_PACK = "com.oplus.omkm2x.net.protocol.DataPack";
    private static final String OMK_CONTEXT = "com.oplus.omkm2x.core.e_z";
    private static final String TEE_SELECTOR = "com.oplus.omkm2x.utils.c";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!DeviceGate.isSupported() || !STDSP.equals(lpp.packageName)
                || lpp.processName == null || !lpp.processName.startsWith(STDSP)) {
            return;
        }
        try {
            // This port has a CryptoEng binder for other ColorOS features but
            // not the OMK TA command family.  Tell only StdSP to use its
            // documented software branch.  Besides selecting tee.e_c, this
            // makes the stock authentication callback populate e_z.g with the
            // lock-screen-authorized OSEC session key before CMK wrapping.
            XposedHelpers.findAndHookMethod(TEE_SELECTOR, lpp.classLoader, "e_c",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(Boolean.FALSE);
                        }
                    });
            XposedBridge.log(TAG + ": selected stock software OMK branch in " + lpp.processName);
            final Class<?> softwareTee = XposedHelpers.findClass(TEE_SOFTWARE, lpp.classLoader);
            XposedHelpers.findAndHookMethod(TEE_HARDWARE, lpp.classLoader, "e_a",
                    String.class, String[].class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object fallback = softwareTee.getDeclaredConstructor().newInstance();
                                Object result = XposedHelpers.callMethod(fallback, "e_a",
                                        param.args[0], param.args[1]);
                                param.setResult(result);
                                XposedBridge.log(TAG + ": served OMK 0x322 through stock software OSEC");
                            } catch (Throwable error) {
                                // Leave the original call in place if the packaged stock fallback
                                // cannot initialize.  This preserves its normal error semantics.
                                XposedBridge.log(TAG + ": stock OSEC fallback unavailable");
                                XposedBridge.log(error);
                            }
                        }
                    });
            // 0x323 consumes the server DataPack produced by 0x322.  Its
            // counterpart in e_c decrypts it with the matching software OSEC
            // key pair, so both legs must use the same implementation.
            final Class<?> dataPack = XposedHelpers.findClass(DATA_PACK, lpp.classLoader);
            XposedHelpers.findAndHookMethod(TEE_HARDWARE, lpp.classLoader, "e_a",
                    dataPack, String[].class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object fallback = softwareTee.getDeclaredConstructor().newInstance();
                                Object result = XposedHelpers.callMethod(fallback, "e_a",
                                        param.args[0], param.args[1]);
                                param.setResult(result);
                                XposedBridge.log(TAG + ": served OMK 0x323 through stock software OSEC");
                            } catch (Throwable error) {
                                XposedBridge.log(TAG + ": stock OSEC response fallback unavailable");
                                XposedBridge.log(error);
                            }
                        }
                    });
            // 0x325 encrypts the registration continuation and 0x326
            // decrypts its server response.  They share the OSEC session
            // state established by 0x322/0x323.
            XposedHelpers.findAndHookMethod(TEE_HARDWARE, lpp.classLoader, "e_a",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object fallback = softwareTee.getDeclaredConstructor().newInstance();
                                param.setResult(XposedHelpers.callMethod(fallback, "e_a", param.args[0]));
                                XposedBridge.log(TAG + ": served OMK 0x325 through stock software OSEC");
                            } catch (Throwable error) {
                                XposedBridge.log(TAG + ": stock OSEC request continuation unavailable");
                                XposedBridge.log(error);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(TEE_HARDWARE, lpp.classLoader, "e_a",
                    dataPack, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object fallback = softwareTee.getDeclaredConstructor().newInstance();
                                param.setResult(XposedHelpers.callMethod(fallback, "e_a", param.args[0]));
                                XposedBridge.log(TAG + ": served OMK 0x326 through stock software OSEC");
                            } catch (Throwable error) {
                                XposedBridge.log(TAG + ": stock OSEC response continuation unavailable");
                                XposedBridge.log(error);
                            }
                        }
                    });
            // 0x328 handles the successful registration response and wraps
            // the returned CMK for local storage.  e_c performs the same
            // operation using the just-established OSEC session.
            final Class<?> omkContext = XposedHelpers.findClass(OMK_CONTEXT, lpp.classLoader);
            XposedHelpers.findAndHookMethod(TEE_HARDWARE, lpp.classLoader, "e_a",
                    omkContext, dataPack, String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object fallback = softwareTee.getDeclaredConstructor().newInstance();
                                Object result = XposedHelpers.callMethod(fallback, "e_a",
                                        param.args[0], param.args[1], param.args[2]);
                                param.setResult(result);
                                XposedBridge.log(TAG + ": served OMK 0x328 through stock software OSEC");
                            } catch (Throwable error) {
                                XposedBridge.log(TAG + ": stock OSEC CMK response fallback unavailable");
                                XposedBridge.log(error);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": installed 0x322/0x323/0x325/0x326/0x328 OSEC chain in " + lpp.processName);
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": installation failed");
            XposedBridge.log(error);
        }
    }
}
