# Run after linking libairrelay_native.so (CMakeLists.txt, POST_BUILD). Fails the build if
# GPL-3.0-only code that must live outside the library is found in it:
#   - playfair: any of its function names in the symbol table
#   - UxPlay's lib/crypto.c: its error message text ("Crypto error at", observed when the
#     0.1.0 binary failed; AirRelay's crypto/airrelay_crypto.c never prints it)
# The library at this point is not stripped yet. That it still has a readable symbol table
# is checked too (our own function must be found), otherwise "nothing found" would prove
# nothing. The helper program must contain playfair.
foreach(v LIB HELPER)
    if(NOT EXISTS "${${v}}")
        message(FATAL_ERROR "CheckLicenseSplit: ${v} not found: ${${v}}")
    endif()
endforeach()
set(PLAYFAIR_NAMES "^(playfair_decrypt|garble|modified_md5|generate_session_key|generate_key_schedule|decryptMessage|sap_hash|default_sap)$")
file(STRINGS "${LIB}" playfair_in_lib REGEX "${PLAYFAIR_NAMES}")
file(STRINGS "${LIB}" old_crypto_in_lib REGEX "Crypto error at")
file(STRINGS "${LIB}" own_symbol REGEX "^airrelay_playfair_decrypt$")
file(STRINGS "${HELPER}" playfair_in_helper REGEX "${PLAYFAIR_NAMES}")
if(playfair_in_lib)
    message(FATAL_ERROR "CheckLicenseSplit: playfair is linked into ${LIB}: ${playfair_in_lib}")
endif()
if(old_crypto_in_lib)
    message(FATAL_ERROR "CheckLicenseSplit: UxPlay lib/crypto.c is linked into ${LIB}")
endif()
if(NOT own_symbol)
    message(FATAL_ERROR "CheckLicenseSplit: no symbol table in ${LIB} (airrelay_playfair_decrypt not found), the check would see nothing")
endif()
if(NOT playfair_in_helper)
    message(FATAL_ERROR "CheckLicenseSplit: ${HELPER} does not contain playfair")
endif()
message(STATUS "CheckLicenseSplit: ok (no playfair, no lib/crypto.c in the library; helper has playfair)")
