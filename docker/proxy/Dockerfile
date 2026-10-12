FROM ubuntu:24.04

RUN apt-get update && apt-get install -y --no-install-recommends squid iptables ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /run/squid /var/log/squid \
    && chown -R proxy:proxy /run/squid /var/log/squid
COPY squid.conf /etc/squid/squid.conf
COPY start-proxy.sh /usr/local/bin/start-proxy.sh
RUN sed -i 's/\r$//' /usr/local/bin/start-proxy.sh
EXPOSE 3128
ENTRYPOINT ["/bin/sh", "/usr/local/bin/start-proxy.sh"]
