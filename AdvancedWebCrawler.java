import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

class CrawlerConfig {
    public String userAgent = "Mozilla/5.0 (CDVA WAIS Security AdvancedJavaCrawler/1.0)";
    public int maxDepth = 3;
    public int requestTimeoutMillis = 5000;
    public int maxRetries = 3;
    public long retryDelayMillis = 2000;
    public long domainDelayMillis = 1000;
    public boolean respectRobotsTxt = true;
    public boolean sameDomainOnly = true;
    public String startUrl = "https://iaac-aeic.gc.ca/050/evaluations/index?culture=en-CA";

    public CrawlerConfig() {}
}

class UrlDepthPair {
    String url;
    int depth;

    UrlDepthPair(String url, int depth) {
        this.url = url;
        this.depth = depth;
    }
}

class RobotsRules {
    public List<Pattern> disallowPatterns = new ArrayList<>();
    public Instant fetchedTime = Instant.now();
}

class RobotsTxtParser {
    private static final Pattern DISALLOW_PATTERN = Pattern.compile("^Disallow:\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern USERAGENT_PATTERN = Pattern.compile("^User-agent:\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    public static RobotsRules parse(URL robotsUrl, String userAgent) {
        RobotsRules rules = new RobotsRules();

        try {
            HttpURLConnection conn = (HttpURLConnection) robotsUrl.openConnection();
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            if (conn.getResponseCode() != 200) {
                return rules; 
            }

            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                boolean applies = false;
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    java.util.regex.Matcher uaMatch = USERAGENT_PATTERN.matcher(line);
                    if (uaMatch.find()) {
                        String ua = uaMatch.group(1).trim();
                        applies = ua.equals("*");
                    } else if (applies) {
                        java.util.regex.Matcher disallowMatch = DISALLOW_PATTERN.matcher(line);
                        if (disallowMatch.find()) {
                            String path = disallowMatch.group(1).trim();
                            if (!path.isEmpty()) {
                                String regex = "^" + Pattern.quote(path).replace("\\*", ".*") + ".*$";
                                rules.disallowPatterns.add(Pattern.compile(regex));
                           }
                       }
                   }
                }
            }
        } catch (IOException e) {
        }

        return rules;
    }

    public static boolean isAllowed(String url, RobotsRules rules) {
        for (Pattern p : rules.disallowPatterns) {
            if (p.matcher(url).matches()) {
                return false;
            }
        }
        return true;
    }
}

public class AdvancedWebCrawler {

    private CrawlerConfig config;
    private Set<String> visited = ConcurrentHashMap.newKeySet();
    private BlockingQueue<UrlDepthPair> frontier = new LinkedBlockingQueue<>();
    private Map<String, RobotsRules> robotsCache = new ConcurrentHashMap<>();
    private Map<String, Instant> domainLastAccess = new ConcurrentHashMap<>();

    public AdvancedWebCrawler(CrawlerConfig config) {
        this.config = config;
    }

    public void startCrawl() {
        try {
            URL start = new URL(config.startUrl);
            frontier.offer(new UrlDepthPair(start.toString(), 0));
            visited.add(start.toString());

            while (!frontier.isEmpty()) {
                UrlDepthPair current = frontier.poll();
                if (current == null) continue;
                if (current.depth > config.maxDepth) {
                    continue;
                }

                respectPoliteness(current.url);

               if (config.respectRobotsTxt && !isAllowedByRobots(current.url)) {
                    System.out.println("Blocked by robots.txt: " + current.url);
                    continue;
                }

                Document doc = fetchWithRetries(current.url);
                if (doc == null) {
                //no fetch
                    continue;
                }

                System.out.println("Crawled (" + current.depth + "): " + current.url);

                Elements links = doc.select("a[href]");
                for (Element link : links) {
                    String foundUrl = link.absUrl("href");
                    if (shouldVisit(foundUrl, current.depth + 1)) {
                        visited.add(foundUrl);
                       frontier.offer(new UrlDepthPair(foundUrl, current.depth + 1));
                    }
              }
            }
        } catch (MalformedURLException e) {
            System.err.println("Invalid start URL");
        }
    }

    private boolean shouldVisit(String url, int depth) {
        if (depth > config.maxDepth) return false;
        if (visited.contains(url)) return false;
        if (!isValidUrl(url)) return false;
        if (config.sameDomainOnly && !isSameDomain(config.startUrl, url)) return false;

        return true;
    }

    private boolean isValidUrl(String url) {
        if (url.startsWith("mailto:")) return false;
        String lower = url.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".pdf") || lower.startsWith("javascript:")) return false;

        try {
            new URL(url);
            return true;
        } catch (MalformedURLException e) {
            return false;
        }
    }

    private boolean isSameDomain(String baseUrl, String candidate) {
        try {
            URL base = new URL(baseUrl);
            URL cand = new URL(candidate);
            return base.getHost().equalsIgnoreCase(cand.getHost());
        } catch (MalformedURLException e) {
            return false;
        }
    }

    private Document fetchWithRetries(String pageUrl) {
        int attempts = 0;
        while (attempts < config.maxRetries) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(pageUrl).openConnection();
                conn.setRequestProperty("User-Agent", config.userAgent);
                conn.setConnectTimeout(config.requestTimeoutMillis);
                conn.setReadTimeout(config.requestTimeoutMillis);

                int status = conn.getResponseCode();
                if (status == 200) {
                    return Jsoup.parse(conn.getInputStream(), null, pageUrl);
                } else if (status == 429 || (status >= 500 && status < 600)) {
                    attempts++;
                    System.err.println("Received HTTP " + status + " for " + pageUrl + ", retrying...");
                    Thread.sleep(config.retryDelayMillis);
                } else {
                    System.err.println("Non-retriable HTTP error " + status + " for " + pageUrl);
                    return null;
                }
            } catch (IOException e) {
                attempts++;
                System.err.println("Network error fetching " + pageUrl + ": " + e.getMessage() + ", attempt " + attempts);
                try {
                    Thread.sleep(config.retryDelayMillis);
                } catch (InterruptedException ignored) {}
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private boolean isAllowedByRobots(String pageUrl) {
        try {
            URL url = new URL(pageUrl);
            String host = url.getHost().toLowerCase();
            RobotsRules rules = robotsCache.get(host);
            if (rules == null) {
                URL robotsUrl = new URL(url.getProtocol() + "://" + host + "/robots.txt");
                rules = RobotsTxtParser.parse(robotsUrl, config.userAgent);
                robotsCache.put(host, rules);
            }

            return RobotsTxtParser.isAllowed(pageUrl, rules);
        } catch (MalformedURLException e) {
            return true;
        }
    }

    private void respectPoliteness(String pageUrl) {
        try {
            URL url = new URL(pageUrl);
            String host = url.getHost().toLowerCase();
            Instant lastAccess = domainLastAccess.get(host);
            Instant now = Instant.now();
            if (lastAccess != null) {
                long elapsed = java.time.Duration.between(lastAccess, now).toMillis();
                if (elapsed < config.domainDelayMillis) {
                    long sleepTime = config.domainDelayMillis - elapsed;
                    Thread.sleep(sleepTime);
                }
            }
            domainLastAccess.put(host, Instant.now());
        } catch (Exception e) {
        }
    }

    public static void main(String[] args) {
        CrawlerConfig config = new CrawlerConfig();
        config.startUrl = "https://iaac-aeic.gc.ca/050/evaluations/index?culture=en-CA";
        config.maxDepth = 3;
        config.respectRobotsTxt = true;
        config.sameDomainOnly = true;
        config.domainDelayMillis = 2000;
        config.maxRetries = 3;
        config.retryDelayMillis = 3000;

        AdvancedWebCrawler crawler = new AdvancedWebCrawler(config);
        crawler.startCrawl();
    }
}
