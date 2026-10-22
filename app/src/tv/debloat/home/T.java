package tv.debloat.home;

/** Text with {name} placeholders: T.f("{n} apps", "n", 12) → "12 apps". */
final class T {
    private T() {}

    static String f(String pattern, Object... pairs) {
        String out = pattern;
        for (int i = 0; i + 1 < pairs.length; i += 2) out = out.replace("{" + pairs[i] + "}", String.valueOf(pairs[i + 1]));
        return out;
    }
}
