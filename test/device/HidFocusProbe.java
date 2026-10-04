import java.lang.reflect.Method;
import java.util.regex.Pattern;

/** Runs the installed APK's actual focus parser as shell without sending input. */
public final class HidFocusProbe {
  /** Prints only dump sizes, predicate counts, and the parser result. */
  public static void main(String[] args) throws Exception {
    Class<?> type = Class.forName("com.pckeyboard.ime.remote.HidFocusGuardKt");
    Method read = type.getDeclaredMethod("readFocusDump", String.class);
    read.setAccessible(true);
    long start = System.nanoTime();
    String window = (String) read.invoke(null, "window");
    String input = (String) read.invoke(null, "input_method");
    System.out.println("elapsed_ms=" + (System.nanoTime() - start) / 1000000);
    System.out.println("window_chars=" + (window == null ? -1 : window.length()));
    System.out.println("input_chars=" + (input == null ? -1 : input.length()));
    if (window == null || input == null) return;
    System.out.println("focus_count=" + count("(?m)^\\s*mCurrentFocus=(?!null)(.+)$", window));
    System.out.println("served_view_count=" + count("(?m)^\\s*mServedView=(.+)$", input));
    System.out.println("connection_count=" + count("(?m)^\\s*mServedInputConnection=(.+)$", input));
    System.out.println("physical_view=" + input.contains("mServedView=com.remote.inputdevice.view.GVDeviceInputView{"));
    System.out.println("null_connection=" + input.contains("mServedInputConnection=null"));
    java.util.regex.Matcher fields = Pattern.compile("(?m)^\\s*(mServedView|mServedInputConnection)=(.*)$").matcher(input);
    while (fields.find()) {
      String value = fields.group(2).trim();
      String kind = value.split("[\\s{@]", 2)[0];
      System.out.println(fields.group(1) + "_class=" + kind);
    }
    Class<?> regexType = Class.forName("kotlin.text.Regex");
    Class<?> sequenceType = Class.forName("kotlin.sequences.Sequence");
    Class<?> matchType = Class.forName("kotlin.text.MatchResult");
    String[] expressions = {"(?:mTopFocusedDisplayId|mFocusedDisplayId)=(-?\\d+)",
        "(?m)^\\s*mCurrentFocus=(.+)$", "(?m)^\\s*mServedView=(.+)$",
        "(?m)^\\s*mServedInputConnection=(.+)$"};
    for (int i = 0; i < expressions.length; i++) {
      Object regex = regexType.getConstructor(String.class).newInstance(expressions[i]);
      Object sequence = regexType.getMethod("findAll", CharSequence.class, int.class)
          .invoke(regex, i < 2 ? window : input, 0);
      java.util.Iterator<?> iterator = (java.util.Iterator<?>) sequenceType.getMethod("iterator").invoke(sequence);
      for (int j = 0; j < 5 && iterator.hasNext(); j++) {
        Object match = iterator.next();
        System.out.println("regex_" + i + "_match_" + j + "_range=" + matchType.getMethod("getRange").invoke(match));
      }
    }
    try {
      Method check = type.getDeclaredMethod("isUuHidFocusSafe", String.class, String.class);
      check.setAccessible(true);
      System.out.println("safe=" + check.invoke(null, window, input));
      String fixtureWindow = "mTopFocusedDisplayId=0\n  mCurrentFocus=null\n"
          + "  mCurrentFocus=Window{test u0 com.netease.uuremote/"
          + "com.remote.app.ui.activity.ScreenActivity type=1}\n";
      String fixtureInput = "  mServedView=com.remote.inputdevice.view.GVDeviceInputView{test}\n"
          + "  mServedInputConnection=null\n";
      verify(check, fixtureWindow, fixtureInput, true);
      verify(check, fixtureWindow.replace(" type=1", ""), fixtureInput, true);
      verify(check, fixtureWindow.replace("com.netease.uuremote/", "other.package/"), fixtureInput, false);
      verify(check, fixtureWindow, fixtureInput.replace("GVDeviceInputView", "InvisibleEditText"), false);
      verify(check, fixtureWindow.replace("DisplayId=0", "DisplayId=1"), fixtureInput, false);
      System.out.println("android_parser_regressions=5_passed");
    } catch (java.lang.reflect.InvocationTargetException failure) {
      Throwable cause = failure.getCause();
      System.out.println("parser_error=" + cause.getClass().getName());
      if (cause instanceof java.util.regex.PatternSyntaxException) {
        System.out.println("pattern_error=" + ((java.util.regex.PatternSyntaxException) cause).getDescription());
        System.out.println("pattern_index=" + ((java.util.regex.PatternSyntaxException) cause).getIndex());
      }
      System.exit(1);
    }
    System.exit(0);
  }

  /** Exercises the installed parser on Android's regex engine, unlike host JVM unit tests. */
  private static void verify(Method check, String window, String input, boolean expected) throws Exception {
    if (!Boolean.valueOf(expected).equals(check.invoke(null, window, input))) {
      throw new AssertionError("Android focus parser regression");
    }
  }

  /** Counts matches without retaining or printing UI text. */
  private static int count(String expression, String input) {
    java.util.regex.Matcher matcher = Pattern.compile(expression).matcher(input);
    int count = 0;
    while (matcher.find()) count++;
    return count;
  }
}
