#include <CoreFoundation/CoreFoundation.h>
#include <jni.h>
#include <string.h>

// Replicates the macOS UI-language resolution that HotSpot/JBR performs in
// java_props_macosx.c (getMacOSXLocale for LC_MESSAGES). SubstrateVM never runs
// that startup code, so the default locale stays "C"/en; we restore it by
// reading CoreFoundation directly.
//
// Returns the most-preferred UI language as a BCP-47 tag (e.g. "fr-FR",
// "zh-Hans-CN"), suitable for java.util.Locale.forLanguageTag(). Returns NULL
// when CoreFoundation has no preferred language (caller keeps the env locale).

#define LOCALEIDLENGTH 128

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_graalvm_locale_NativeLocaleBridge_nativePreferredLanguageTag(
    JNIEnv *env, jclass clazz) {
    char languageString[LOCALEIDLENGTH];
    char localeString[LOCALEIDLENGTH];

    CFArrayRef languages = CFLocaleCopyPreferredLanguages();
    if (languages == NULL) {
        return NULL;
    }
    if (CFArrayGetCount(languages) <= 0) {
        CFRelease(languages);
        return NULL;
    }

    CFStringRef primaryLanguage = (CFStringRef)CFArrayGetValueAtIndex(languages, 0);
    if (primaryLanguage == NULL) {
        CFRelease(languages);
        return NULL;
    }
    // UTF-8 keeps the BCP-47 tag intact for JNI NewStringUTF (modified UTF-8);
    // language/script/region subtags are ASCII anyway.
    if (!CFStringGetCString(primaryLanguage, languageString, LOCALEIDLENGTH,
                            kCFStringEncodingUTF8)) {
        CFRelease(languages);
        return NULL;
    }
    CFRelease(languages);

    // Explicitly supply the region when the preferred language carries none
    // ("en") or only a script ("en-Latn", 5 trailing chars). Mirrors the
    // region back-fill in getMacOSXLocale(LC_MESSAGES).
    char *hyphenPos = strchr(languageString, '-');
    int langStrLen = (int)strlen(languageString);
    if (hyphenPos == NULL || (languageString + langStrLen - hyphenPos) == 5) {
        CFLocaleRef cflocale = CFLocaleCopyCurrent();
        if (cflocale != NULL) {
            if (CFStringGetCString(CFLocaleGetIdentifier(cflocale), localeString,
                                   LOCALEIDLENGTH, kCFStringEncodingUTF8)) {
                char *underscorePos = strrchr(localeString, '_');
                if (underscorePos != NULL) {
                    const char *region = underscorePos + 1;
                    if (langStrLen + 1 + (int)strlen(region) < LOCALEIDLENGTH) {
                        strcat(languageString, "-");
                        strcat(languageString, region);
                    }
                }
            }
            CFRelease(cflocale);
        }
    }

    return (*env)->NewStringUTF(env, languageString);
}
