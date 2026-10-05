#!/usr/bin/env perl
# new-project.sh 가 찍은 프로젝트(현재 디렉토리)의 capabilities.json 을 고른 모듈 · 앱만 남기도록 걸러 stamped 모드로 다시 쓴다.
# 생성물(docs/capabilities.md · llms.txt)은 이어지는 rename-skeleton.sh 가 새 이름으로 다시 만든다.
#
#   perl <스켈레톤>/scripts/new-project.d/stamp-capabilities.pl --selected "a b c" --apps "api sample" --db postgresql|mysql
#
# 하는 일: 안 고른 모듈 · 앱 항목을 지우고(요약은 stampedFrom.omitted 로 남겨 스켈레톤을 가리킨다), 남은 항목의 참조(autoIncludes · needs)에서 지운 것을 뺀다.
# 조각 · 결정표의 명령은 스켈레톤에서나 쓰는 것이라 지운다(newProjectFlag · decisions[].flag → null, examples · newProject 삭제).
# 모든 항목이 남은 결정표 줄만 남긴다. starterModules · starter 는 찍은 apps/api 의 의존 닫힘으로 다시 계산한다(고른 모듈이 들어가고, MySQL 이면 db-mysql).
use strict;
use warnings;
use utf8;
use JSON::PP;
use Getopt::Long;
binmode(STDOUT, ':encoding(UTF-8)');
binmode(STDERR, ':encoding(UTF-8)');

my ($selected, $apps, $db) = ('', 'api', 'postgresql');
GetOptions('selected=s' => \$selected, 'apps=s' => \$apps, 'db=s' => \$db) or die "usage: --selected \"a b\" --apps \"api\" --db postgresql|mysql\n";
my %mod = map { $_ => 1 } split(/\s+/, $selected);
my %app = map { $_ => 1 } split(/\s+/, $apps);

require './scripts/build-capabilities.pl';   # load_catalog · canonical_text · spew (main::)
my $cat = main::load_catalog('.');
die "이미 stamped 카탈로그다\n" if $cat->{mode} eq 'stamped';

# 찍은 프로젝트의 「스타터」 = 찍은 apps/api 가 이미 가진 모듈(스타터 + --modules, 의존으로 닫힌 것 · MySQL 이면 db-mysql). 빌드 파일에서 읽는다.
sub project_deps {
  my ($file) = @_;
  open(my $fh, '<:encoding(UTF-8)', $file) or return ();
  my @out;
  while (my $line = <$fh>) {
    $line =~ s{//.*}{};
    while ($line =~ /\b(api|implementation|runtimeOnly|compileOnly|testImplementation|testRuntimeOnly)\(project\(":modules:([a-z0-9-]+)"\)\)/g) { push @out, [ $1, $2 ]; }
  }
  close($fh);
  return @out;
}
my %starter;
{
  my @queue = map { $_->[1] } grep { $_->[0] !~ /^test|^compileOnly$/ } project_deps('apps/api/build.gradle.kts');
  while (@queue) {
    my $m = shift @queue;
    next if $starter{$m}++;
    push @queue, map { $_->[1] } project_deps("modules/$m/build.gradle.kts");   # 모듈끼리는 모든 종류의 의존을 따라간다(new-project.sh 의 닫힘과 같다)
  }
}
my @starter = sort keys %starter;

my (@kept, @omitted);
for my $e (@{ $cat->{capabilities} }) {
  my $keep;
  if ($e->{kind} eq 'module') { $keep = $mod{ $e->{module} }; }
  elsif ($e->{kind} eq 'app') { (my $n = $e->{path}) =~ s{^apps/}{}; $keep = $app{$n}; }
  else { $keep = -e $e->{path}; }          # 스크립트는 new-project.sh 가 이미 가지친 결과를 따른다
  if ($keep) { push @kept, $e; }
  elsif ($e->{kind} ne 'script') { push @omitted, { id => $e->{id}, summary => $e->{summary} }; }
}
my %ids = map { $_->{id} => 1 } @kept;

for my $e (@kept) {
  $e->{newProjectFlag} = undef;
  $e->{starter} = JSON::PP::true if $e->{kind} eq 'module' && $starter{ $e->{module} };
  $e->{starter} = JSON::PP::false if $e->{kind} eq 'module' && !$starter{ $e->{module} };
  $e->{autoIncludes} = [ grep { $ids{$_} } @{ $e->{autoIncludes} } ];
  my $n = $e->{needs};
  $n->{requires} = [ grep { $ids{$_} } @{ $n->{requires} } ];
  $n->{optional} = [ grep { $ids{$_} } @{ $n->{optional} } ];
  $n->{oneOf} = [ grep { @$_ } map { [ grep { $ids{$_} } @$_ ] } @{ $n->{oneOf} } ];
}

my @decisions;
for my $d (@{ $cat->{decisions} }) {
  next unless @{ $d->{modules} };                        # 프런트만의 줄은 이 백엔드 프로젝트에 쓸모없다
  next if grep { !$ids{$_} } @{ $d->{modules} };
  my @groups = map { [ grep { $ids{$_} } @$_ ] } @{ $d->{oneOf} };
  next if grep { !@$_ } @groups;
  $d->{oneOf} = \@groups;
  $d->{flag} = undef;
  push @decisions, $d;
}

my @guides = grep { -e $_->{path} } @{ $cat->{guides} };

$cat->{mode} = 'stamped';
$cat->{summary} = '스켈레톤에서 찍은 백엔드 프로젝트 — 아래는 이 프로젝트에 남은 모듈 · 앱 · 스크립트다(빠진 것은 맨 아래 「이 프로젝트에 없는 것」).';
$cat->{starterModules} = [ sort @starter ];
$cat->{capabilities} = \@kept;
$cat->{decisions} = \@decisions;
$cat->{guides} = \@guides;
$cat->{stampedFrom} = { omitted => \@omitted };
delete $cat->{examples};
delete $cat->{newProject};
main::spew('capabilities.json', main::canonical_text($cat));
printf("capabilities: %d entries kept, %d omitted, %d decisions\n", scalar(@kept), scalar(@omitted), scalar(@decisions));
