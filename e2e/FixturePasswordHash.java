import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Local test launcher helper: reads only a one-process secret, never prints plaintext. */
class FixturePasswordHash {
    public static void main(String[] args) {
        String secret = System.getenv("CAREER_E2E_HASH_INPUT");
        if (secret == null || secret.length() < 32) throw new IllegalArgumentException("Random fixture secret required");
        System.out.print(new BCryptPasswordEncoder(10).encode(secret));
    }
}
