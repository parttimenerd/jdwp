package tunnel.util;

public enum ToStringMode {
    STRING,
    CODE() {
        @Override
        public String format(Object obj) {
            return obj instanceof ToCode ? ((ToCode) obj).toCode() : obj.toString();
        }
    },
    SHORT() {
        @Override
        public String format(Object obj) {
            return obj instanceof ToShortString ? ((ToShortString) obj).toShortString() : obj.toString();
        }
    };

    public String format(Object obj) {
        return obj.toString();
    }
}
