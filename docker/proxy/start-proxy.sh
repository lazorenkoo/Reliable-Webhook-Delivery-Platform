set -eu

iptables -P OUTPUT DROP
iptables -A OUTPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
iptables -A OUTPUT -p udp -d 127.0.0.1 --dport 1024:65535 -j ACCEPT
iptables -A OUTPUT -p udp -d 127.0.0.11 -m conntrack --ctorigdstport 53 -j ACCEPT
iptables -A OUTPUT -p tcp -d 127.0.0.11 -m conntrack --ctorigdstport 53 -j ACCEPT
for range in 0.0.0.0/8 10.0.0.0/8 100.64.0.0/10 127.0.0.0/8 \
    169.254.0.0/16 172.16.0.0/12 192.0.0.0/24 192.0.2.0/24 \
    192.88.99.0/24 192.168.0.0/16 198.18.0.0/15 \
    198.51.100.0/24 203.0.113.0/24 224.0.0.0/3; do
    iptables -A OUTPUT -d "$range" -j REJECT
done
iptables -A OUTPUT -p tcp -m multiport --dports 80,443 -j ACCEPT
ip6tables -P OUTPUT DROP
ip6tables -A OUTPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
ip6tables -A OUTPUT -p udp -d ::1 --dport 1024:65535 -j ACCEPT

exec squid -N -f /etc/squid/squid.conf
