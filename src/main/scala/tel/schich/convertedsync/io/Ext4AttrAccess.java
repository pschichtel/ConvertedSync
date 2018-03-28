package tel.schich.convertedsync.io;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.nio.file.attribute.UserDefinedFileAttributeView;

public class Ext4AttrAccess {
    private static final Constructor<?> LINUX_DOS_FILE_ATTRIBUTE_VIEW_CTOR =
            get(get("sun.nio.fs.LinuxUserDefinedFileAttributeView"), get("sun.nio.fs.UnixPath"), boolean.class);

    static {
        if (LINUX_DOS_FILE_ATTRIBUTE_VIEW_CTOR != null) {
            LINUX_DOS_FILE_ATTRIBUTE_VIEW_CTOR.setAccessible(true);
        }
    }

    public static UserDefinedFileAttributeView getView(Path path) throws InvocationTargetException, IllegalAccessException, InstantiationException {
        Object view = LINUX_DOS_FILE_ATTRIBUTE_VIEW_CTOR.newInstance(path, false);
        return (UserDefinedFileAttributeView) view;
    }

    private static Class<?> get(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private static Constructor<?> get(Class<?> clazz, Class<?>... params) {
        if (clazz == null) {
            return null;
        }
        try {
            return clazz.getDeclaredConstructor(params);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
