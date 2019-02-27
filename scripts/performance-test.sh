#!/usr/bin/env bash

generate_queries() {
  local code=""
  read -d '' code <<-EOF
my @hosts = @ARGV;
die "Usage: \$0 service-a [[service-b] ...]\n" unless (@hosts);

##################################################################
my \$num = \$#hosts + 1;
while (1) {
  my \$idx = rand(\$num);
  print \$hosts[\$idx], " A\\\\n";
}
EOF

  perl -e "$code" "$@"
}

if [ -z "$1" ]; then
  cat <<EOF
USAGE: $0 <dns-name> <dns-name> ...

EXAMPLE:
  $0 {some-service,foo,bar,database,nginx,web,api}.service.eureka

EOF
  exit 1
fi

generate_queries "$@" | dnsperf -s 127.0.0.1 -p 8553 -l 20 -c 100

# vim:shiftwidth=2 softtabstop=2 expandtab
# EOF
