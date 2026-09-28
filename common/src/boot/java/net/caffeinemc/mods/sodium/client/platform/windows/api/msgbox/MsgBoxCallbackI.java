package net.caffeinemc.mods.sodium.client.platform.windows.api.msgbox;

import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.NativeType;

import java.lang.invoke.MethodHandles;

import static org.lwjgl.system.APIUtil.apiCreateCIF;
import static org.lwjgl.system.MemoryUtil.memGetAddress;
import static org.lwjgl.system.libffi.LibFFI.*;

@FunctionalInterface
@NativeType("MSGBOXCALLBACK")
public interface MsgBoxCallbackI extends CallbackI {
    Callback.Descriptor CIF = createDescriptor();

    private static Callback.Descriptor createDescriptor() {
        var cif = apiCreateCIF(
                FFI_DEFAULT_ABI,
                ffi_type_void,
                ffi_type_pointer
        );
        try {
            // LWJGL 3.4.3+ has (Class, MethodHandles.Lookup, FFICIF)
            return Callback.Descriptor.class.getConstructor(Class.class, MethodHandles.Lookup.class, org.lwjgl.system.libffi.FFICIF.class)
                    .newInstance(MsgBoxCallbackI.class, MethodHandles.lookup(), cif);
        } catch (NoSuchMethodException e) {
            try {
                // LWJGL 3.4.1 has (MethodHandles.Lookup, FFICIF)
                return Callback.Descriptor.class.getConstructor(MethodHandles.Lookup.class, org.lwjgl.system.libffi.FFICIF.class)
                        .newInstance(MethodHandles.lookup(), cif);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException("Failed to create Callback.Descriptor for MsgBoxCallbackI", ex);
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to create Callback.Descriptor for MsgBoxCallbackI", e);
        }
    }

    @Override
    default Callback.Descriptor getDescriptor() {
        return CIF;
    }

    @Override
    default void callback(long ret, long args) {
        this.invoke(
                memGetAddress(memGetAddress(args)) /* lpHelpInfo */
        );
    }

    void invoke(@NativeType("LPHELPINFO *") long lpHelpInfo);
}
