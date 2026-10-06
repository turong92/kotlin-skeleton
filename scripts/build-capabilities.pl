#!/usr/bin/env perl
# capabilities.json (정본) → docs/capabilities.md · llms.txt 를 만든다. 사람이 읽는 목록은 여기서만 나온다 — 손으로 고치지 않는다.
#
#   perl scripts/build-capabilities.pl            # capabilities.json 을 정본 형식으로 다시 쓰고 생성물을 쓴다
#   perl scripts/build-capabilities.pl --check    # 쓰지 않고, 어긋나면 종료 코드 1 (./gradlew check 의 CapabilitiesCatalogTest · CI 가 부른다)
#
# 새 모듈 · 앱 · 스크립트를 더했을 때: CapabilitiesCatalogTest 가 실패하며 붙일 객체를 그대로 보여 준다 → capabilities.json 의 "capabilities" 에 붙이고
# TODO 를 채운 뒤 이 스크립트를 한 번 돌린다(항목 순서 · 들여쓰기는 알아서 정리된다). 스키마: docs/capabilities.schema.json.
# 의존은 perl 5.14+ 의 core 모듈(JSON::PP)뿐이다 — new-project.sh 와 같은 전제(perl)를 쓴다.
use strict;
use warnings;
use utf8;
use JSON::PP;
use File::Basename qw(dirname);
use File::Spec;
use Cwd qw(abs_path);

binmode(STDOUT, ':encoding(UTF-8)');
binmode(STDERR, ':encoding(UTF-8)');

my $ROOT = abs_path(File::Spec->catdir(dirname(abs_path(__FILE__)), '..'));
my $CATALOG = 'capabilities.json';
# 이 카탈로그를 찍어 낸 스켈레톤 레포 이름 — .pl 은 rename-skeleton.sh 가 건드리지 않아 찍은 프로젝트에서도 그대로 남는다
my $ORIGIN = 'kotlin-skeleton';

# ---------------------------------------------------------------------------------------------------- 읽기 · 쓰기

sub slurp {
  my ($path) = @_;
  open(my $fh, '<:encoding(UTF-8)', $path) or return undef;
  local $/;
  my $text = <$fh>;
  close($fh);
  return $text;
}

sub spew {
  my ($path, $text) = @_;
  open(my $fh, '>:encoding(UTF-8)', $path) or die "쓸 수 없다: $path ($!)\n";
  print $fh $text;
  close($fh);
}

sub load_catalog {
  my ($root) = @_;
  my $text = slurp("$root/$CATALOG");
  die "$CATALOG 이 없다 ($root)\n" unless defined $text;
  my $cat = JSON::PP->new->decode($text);    # 문자열은 이미 디코드된 텍스트로 넘긴다
  for my $k (qw(mode name summary starterModules capabilities decisions)) {
    die "$CATALOG: 최상위 '$k' 가 없다\n" unless defined $cat->{$k};
  }
  return $cat;
}

# ---------------------------------------------------------------------------------------------------- 정본 형식 (키 순서 · 항목 순서 · 들여쓰기)

my @KEY_ORDER = qw(
  $schema schemaVersion mode name version id kind module path status summary siblings stampedFrom starterModules newProject guides capabilities decisions examples
  starter newProjectFlag autoIncludes needs config basePaths secrets docs frontend notFor keywords
  repo catalog llms recipe omitted script usage positional flags
  requires oneOf optional prefixes snippet note env whenMissing platformProvided packages ko en
  need modules flag byHand title product extraFlags target package prefix classPrefix frontendCapabilities frontendCommand
);
my %RANK;
@RANK{@KEY_ORDER} = (0 .. $#KEY_ORDER);
# 같은 이름이 층마다 다른 자리에 오는 칸: capabilities(최상위 배열)와 frontend.capabilities 는 같은 순위여도 층이 달라 충돌하지 않는다

my $SCALAR = JSON::PP->new->utf8(0)->allow_nonref->canonical;

sub key_cmp {
  my ($x, $y) = @_;
  my ($rx, $ry) = ($RANK{$x}, $RANK{$y});
  return $rx <=> $ry if defined $rx && defined $ry;
  return -1 if defined $rx;
  return 1 if defined $ry;
  return $x cmp $y;
}

sub is_scalar_ref { my ($v) = @_; return !ref($v) || ref($v) eq 'JSON::PP::Boolean'; }

sub inline_value {
  my ($v) = @_;
  return $SCALAR->encode($v) if is_scalar_ref($v) || !defined $v;
  if (ref($v) eq 'ARRAY' && !grep { ref($_) eq 'HASH' } @$v) {
    return '[' . join(', ', map { inline_value($_) } @$v) . ']';
  }
  return undef;
}

sub render_json {
  my ($v, $indent) = @_;
  $indent //= 0;
  my $pad = '  ' x $indent;
  if (ref($v) eq 'HASH') {
    return '{}' unless %$v;
    my @keys = sort { key_cmp($a, $b) } keys %$v;
    return "{\n" . join(",\n", map { "$pad  " . $SCALAR->encode($_) . ': ' . render_json($v->{$_}, $indent + 1) } @keys) . "\n$pad}";
  }
  if (ref($v) eq 'ARRAY') {
    return '[]' unless @$v;
    my $flat = inline_value($v);
    return $flat if defined $flat && length($flat) <= 100;
    return "[\n" . join(",\n", map { "$pad  " . render_json($_, $indent + 1) } @$v) . "\n$pad]";
  }
  return $SCALAR->encode($v);
}

my %KIND_RANK = (module => 0, app => 1, script => 2);

sub canonical_text {
  my ($cat) = @_;
  my %copy = %$cat;
  $copy{capabilities} = [ sort { $KIND_RANK{$a->{kind}} <=> $KIND_RANK{$b->{kind}} || $a->{id} cmp $b->{id} } @{ $cat->{capabilities} } ];
  $copy{starterModules} = [ sort @{ $cat->{starterModules} } ];
  return render_json(\%copy) . "\n";
}

# ---------------------------------------------------------------------------------------------------- 렌더링 도우미

sub entries { my ($cat) = @_; return @{ $cat->{capabilities} }; }

sub by_id {
  my ($cat) = @_;
  return { map { $_->{id} => $_ } entries($cat) };
}

sub code { my ($t) = @_; return '`' . $t . '`'; }
sub codes { return join(', ', map { code($_) } @_); }
sub cell { my ($t) = @_; $t =~ s/\|/\\|/g; $t =~ s/\n/ /g; return $t; }
sub kw { my ($e, $lang, $n) = @_; my @k = @{ $e->{keywords}{$lang} }; @k = @k[0 .. $n - 1] if defined $n && @k > $n; return join(', ', @k); }

# 한 항목을 켜는 백엔드 조각
sub backend_fragment {
  my ($e, $stamped) = @_;
  return '스타터(apps/api)에 기본 포함' if $e->{starter};
  my $f = $e->{newProjectFlag};
  return '(도구 — 켜는 조각 없음)' if !defined $f && $e->{kind} eq 'script';
  return '(조각 없음)' unless defined $f;
  my $text = code($f);
  for my $group (@{ $e->{needs}{oneOf} }) {
    $text .= ' — ' . join(' | ', map { code($_) } @$group) . ' 중 하나 이상 고른다';
  }
  return $text;
}

# 프런트(react) 조각
sub frontend_fragment {
  my ($e) = @_;
  my $fe = $e->{frontend};
  return '(프런트 짝 없음)' unless $fe;
  return defined $fe->{newProjectFlag} ? code($fe->{newProjectFlag}) : '기본 포함(덧붙일 것 없음)';
}

# 작업 예의 kotlin 명령 (CapabilitiesGuards.exampleCommand 와 같은 모양)
sub example_command {
  my ($x) = @_;
  my $c = "scripts/new-project.sh $x->{target} $x->{package} $x->{prefix} $x->{classPrefix}";
  $c .= ' --modules ' . join(',', @{ $x->{modules} }) if @{ $x->{modules} };
  $c .= " $_" for @{ $x->{extraFlags} };
  return $c;
}

sub starter_line {
  my ($cat) = @_;
  return codes(@{ $cat->{starterModules} });
}

# ---------------------------------------------------------------------------------------------------- docs/capabilities.md

sub render_md {
  my ($cat) = @_;
  my $by = by_id($cat);
  my $stamped = $cat->{mode} eq 'stamped';
  my $out = '';
  $out .= "<!-- 생성물 — capabilities.json 에서 `perl scripts/build-capabilities.pl` 이 만든다. 손으로 고치지 않는다 -->\n\n";
  $out .= "# 기능 카탈로그 — $cat->{name}\n\n$cat->{summary}\n\n";
  $out .= "정본은 `capabilities.json`(스키마 `docs/capabilities.schema.json`)이고 이 문서 · `llms.txt` 는 거기서 만든다. 항목마다 한 줄 요약 · 켜는 법 · 자동으로 따라오는 모듈 · 설정 접두사 · HTTP 경로 · 비밀 · 짝 프런트 · 쓰지 않는 경우 · 한국어/영어 키워드가 있다.\n\n";
  if ($stamped) {
    my $from = $ORIGIN;
    $out .= "> 이 프로젝트는 `$from` 에서 찍었다. 아래는 **이 프로젝트에 남은 것만**이다. 빠진 것은 맨 아래 「이 프로젝트에 없는 것」 — 스켈레톤 레포의 `capabilities.json` · `llms.txt` 에 전부 있다.\n\n";
  }
  $out .= "## 읽는 법\n\n";
  $out .= "- **무엇이 필요하다는 말을 받으면** 아래 결정표에서 그 말(키워드)을 찾아 id · 명령 조각을 고른다. 표에 없으면 「전체 목록」의 키워드 열을 훑는다. 만들기 전에 이미 있는지 먼저 본다.\n";
  $out .= "- 모듈 이름 = id. `자동으로 따라온다` 는 Gradle 의존으로 닫혀 같이 오는 모듈이라 `--modules` 에 적지 않아도 된다. `함께 골라야 한다` 는 자동으로 오지 않지만 없으면 동작하지 않는 짝이다.\n";
  $out .= "- 스타터(`apps/api`)에 이미 있는 모듈: " . starter_line($cat) . " — 조각에서 뺀다.\n";
  $out .= "- 설정 키와 기본값은 `docs/config/modules/<모듈>.yml`, 모듈 한 쪽 문서는 `docs/modules/<모듈>.md`. 환경변수의 `<P>` 는 배포 선언의 `env_prefix`(설정 접두사의 대문자).\n\n";

  unless ($stamped) {
    my $np = $cat->{newProject};
    $out .= "## new-project.sh 인터페이스\n\n";
    $out .= "- kotlin: " . code($np->{usage}) . "\n";
    if ($cat->{siblings} && $cat->{siblings}{frontend}{usage}) {
      $out .= "- react: " . code($cat->{siblings}{frontend}{usage}) . "\n";
    }
    $out .= "- 조각을 합치는 법: `--modules` 는 하나로 합치고(쉼표) 다른 옵션(`--db` · `--with-sample` …)은 그대로 덧붙인다. 의존으로 닫히는 모듈은 적지 않아도 따라온다. `--dry-run` 을 붙이면 아무것도 만들지 않고 고른 모듈과 따라온 이유만 보인다.\n";
    $out .= "- 완성된 예(명령 · 환경변수 · 실행 · 검증 · 배포)와 손으로 써야 하는 것: `docs/new-project-recipe.md`\n\n";
  }

  $out .= "## 필요한 것 → 고를 것\n\n";
  $out .= $stamped
    ? "| 필요한 것 | 항목(id) | react 조각 | 그래도 손으로 써야 하는 것 |\n|---|---|---|---|\n"
    : "| 필요한 것 | 고를 것(id) | kotlin `new-project.sh` 조각 | react `new-project.sh` 조각 | 그래도 손으로 써야 하는 것 |\n|---|---|---|---|---|\n";
  for my $d (@{ $cat->{decisions} }) {
    my $ids = @{ $d->{modules} } ? join(' + ', map { code($_) } @{ $d->{modules} }) : '(백엔드 모듈 없음)';
    for my $g (@{ $d->{oneOf} }) { $ids .= ' + (' . join(' \| ', map { code($_) } @$g) . ')'; }
    my $kf = defined $d->{flag} ? code($d->{flag}) : '(덧붙일 것 없음)';
    for my $g (@{ $d->{oneOf} }) { $kf .= ' — ' . join(' | ', map { code($_) } @$g) . ' 중 하나 이상 고른다'; }   # 아래 cell() 이 | 를 이스케이프한다
    my $ff = $d->{frontend} ? (defined $d->{frontend}{flag} ? code($d->{frontend}{flag}) : '(기본 포함)') . ($d->{frontend}{capabilities} && @{ $d->{frontend}{capabilities} } ? ' — react `' . join('` `', @{ $d->{frontend}{capabilities} }) . '`' : '') : '(프런트 없음)';
    $out .= $stamped
      ? '| ' . join(' | ', cell($d->{need}), $ids, cell($ff), cell($d->{byHand})) . " |\n"
      : '| ' . join(' | ', cell($d->{need}), $ids, cell($kf), cell($ff), cell($d->{byHand})) . " |\n";
  }
  $out .= "\n";

  $out .= "## 전체 목록\n\n";
  my %title = (module => '모듈 (`modules/*`)', app => '앱 (`apps/*`)', script => '스크립트 (`scripts/*`)');
  for my $kind (qw(module app script)) {
    my @es = grep { $_->{kind} eq $kind } entries($cat);
    next unless @es;
    $out .= "### $title{$kind}\n\n";
    $out .= "| id | 요약 | 켜는 조각 | 키워드 (한국어 / 영어) | 짝 프런트 |\n|---|---|---|---|---|\n";
    for my $e (@es) {
      my $fe = $e->{frontend} ? join(' ', map { code($_) } @{ $e->{frontend}{packages} }) : '—';
      $out .= '| ' . join(' | ', code($e->{id}), cell($e->{summary}), cell(backend_fragment($e, $stamped)), cell(kw($e, 'ko') . ' / ' . kw($e, 'en')), cell($fe)) . " |\n";
    }
    $out .= "\n";
  }

  if (!$stamped && $cat->{examples} && @{ $cat->{examples} }) {
    $out .= "## 작업 예 (그대로 찍을 수 있는 두 레포의 명령)\n\n";
    for my $x (@{ $cat->{examples} }) {
      $out .= "### $x->{title}\n\n> $x->{product}\n\n";
      $out .= "- kotlin(이 레포): " . code(example_command($x)) . "\n";
      $out .= "- react: " . code($x->{frontendCommand}) . "\n";
      $out .= "- 짝 프런트 항목: " . codes(@{ $x->{frontendCapabilities} }) . "\n\n";
    }
    $out .= "그다음의 설정 · 실행 · 검증 · 손으로 써야 하는 것: `docs/new-project-recipe.md` 의 같은 이름 예.\n\n";
  }

  $out .= "## 항목 상세\n\n";
  for my $e (entries($cat)) {
    $out .= "### `$e->{id}` — $e->{summary}\n\n";
    $out .= "- 종류 · 상태: $e->{kind} · $e->{status} — 위치 " . code($e->{path}) . "\n";
    $out .= "- 켜는 법: " . backend_fragment($e, $stamped) . "\n";
    $out .= "- 의존 한 줄: " . code('implementation(project(":modules:' . $e->{module} . '"))') . "\n" if $e->{kind} eq 'module';
    my %optional = map { $_ => 1 } @{ $e->{needs}{optional} };
    my @auto_runtime = grep { !$optional{$_} } @{ $e->{autoIncludes} };
    my @auto_compile = grep { $optional{$_} } @{ $e->{autoIncludes} };
    $out .= "- 자동으로 따라온다: " . codes(@auto_runtime) . "\n" if @auto_runtime;
    $out .= "- 소스만 따라온다(컴파일 전용 — 런타임 클래스패스에는 없다. 쓰려면 apps/api 에 의존 한 줄을 더한다): " . codes(@auto_compile) . "\n" if @auto_compile;
    $out .= "- 함께 골라야 한다: " . codes(@{ $e->{needs}{requires} }) . "\n" if @{ $e->{needs}{requires} };
    for my $g (@{ $e->{needs}{oneOf} }) { $out .= "- 하나 이상 고른다: " . join(' | ', map { code($_) } @$g) . "\n"; }
    if ($e->{config}) {
      my @c = @{ $e->{config}{prefixes} };
      my $line = @c ? '설정 접두사 ' . codes(@c) : '설정 접두사 없음';
      $line .= ' — 키와 기본값 ' . code($e->{config}{snippet}) if $e->{config}{snippet};
      $line .= " ($e->{config}{note})" if $e->{config}{note};
      $out .= "- $line\n";
    }
    $out .= "- HTTP 경로: " . codes(@{ $e->{basePaths} }) . "\n" if @{ $e->{basePaths} };
    for my $s (@{ $e->{secrets} }) {
      $out .= '- 비밀 · 환경변수: ' . codes(@{ $s->{env} }) . ($s->{platformProvided} ? ' (배포 플랫폼이 만들어 넣는다)' : '') . " — 빠지면: $s->{whenMissing}\n";
    }
    $out .= "- 문서: " . codes(@{ $e->{docs} }) . "\n";
    if ($e->{frontend}) {
      my $fe = $e->{frontend};
      $out .= "- 짝 프런트(react-skeleton): 항목 " . codes(@{ $fe->{capabilities} }) . ($fe->{packages} && @{ $fe->{packages} } ? ' · 패키지 ' . codes(@{ $fe->{packages} }) : '') . ' · 조각 ' . frontend_fragment($e) . "\n";
    }
    $out .= "- 쓰지 않는 경우:\n" . join('', map { "  - $_\n" } @{ $e->{notFor} });
    $out .= "- 키워드: " . kw($e, 'ko') . ' / ' . kw($e, 'en') . "\n\n";
  }

  if ($stamped && $cat->{stampedFrom} && @{ $cat->{stampedFrom}{omitted} }) {
    $out .= "## 이 프로젝트에 없는 것 (스켈레톤에는 있다)\n\n";
    $out .= "필요하면 스켈레톤 레포에서 그 모듈 디렉토리 · `docs/modules/<모듈>.md` · `docs/config/modules/<모듈>.yml` 을 복사하고 `settings.gradle.kts` 에 `include(\":modules:<모듈>\")`, `apps/api/build.gradle.kts` 에 의존 한 줄을 더한다. 항목의 자세한 내용은 스켈레톤의 `capabilities.json`.\n\n";
    for my $o (@{ $cat->{stampedFrom}{omitted} }) { $out .= '- ' . code($o->{id}) . ": $o->{summary}\n"; }
    $out .= "\n";
  }
  return $out;
}

# ---------------------------------------------------------------------------------------------------- llms.txt

sub render_llms {
  my ($cat) = @_;
  my $stamped = $cat->{mode} eq 'stamped';
  my $out = "# $cat->{name}\n\n> $cat->{summary}\n\n";
  if ($stamped) {
    $out .= "이 프로젝트는 `$ORIGIN` 에서 찍었다. 에이전트는 무엇이든 새로 만들기 전에 아래를 먼저 읽는다 — 이 프로젝트에 이미 있는 모듈을 다시 만들지 않기 위해서다. 목록에 없는 기능은 스켈레톤 레포의 `llms.txt` · `capabilities.json` 에 있을 수 있다(맨 아래).\n\n";
  } else {
    $out .= "새 프로젝트를 시킬 때 에이전트는 무엇이든 새로 만들기 전에 아래를 먼저 읽는다 — 이미 준비된 모듈 · 앱 · 스크립트를 다시 만들지 않기 위해서다.\n\n";
  }
  $out .= "## 먼저 읽을 것\n\n";
  $out .= "- [capabilities.json](capabilities.json): 기능 카탈로그 정본(id · 요약 · 켜는 조각 · 따라오는 모듈 · 설정 접두사 · HTTP 경로 · 비밀 · 짝 프런트 · 쓰지 않는 경우 · 한국어/영어 키워드)\n";
  $out .= "- [docs/capabilities.md](docs/capabilities.md): 「필요한 것 → 고를 것」 결정표(명령 조각 포함)와 전체 목록 · 항목 상세\n";
  for my $g (@{ $cat->{guides} }) { $out .= "- [$g->{path}]($g->{path}): $g->{summary}\n"; }
  $out .= "\n";

  unless ($stamped) {
    $out .= "## 새 프로젝트 세팅 (두 레포 — 각 스켈레톤 레포 루트에서 실행)\n\n";
    $out .= "- kotlin: `" . $cat->{newProject}{usage} . "`\n";
    $out .= "- react: `" . $cat->{siblings}{frontend}{usage} . "`  (react-skeleton 레포에서 실행)\n" if $cat->{siblings} && $cat->{siblings}{frontend}{usage};
    $out .= "- 아래 기능 줄의 「→ kotlin / react」 조각을 합쳐 쓴다: `--modules` 는 하나로(쉼표) 합치고 다른 옵션은 덧붙인다. 의존으로 닫히는 모듈은 적지 않아도 따라온다. `--dry-run` 은 고른 모듈만 보인다. 완성된 예와 손으로 써야 하는 것: docs/new-project-recipe.md · docs/capabilities.md\n";
    $out .= "- 스타터(apps/api)에 이미 있어 조각에 적지 않는 모듈: " . starter_line($cat) . "\n\n";
  }

  $out .= "## 필요한 것 → 고를 것 (결정표)\n\n";
  for my $d (@{ $cat->{decisions} }) {
    my @parts;
    my $kf = defined $d->{flag} ? "kotlin `$d->{flag}`" : ($stamped ? '' : 'kotlin 덧붙일 것 없음');
    for my $g (@{ $d->{oneOf} }) { $kf .= ' (' . join(' | ', @$g) . ' 중 하나 이상 고른다)'; }
    push @parts, $kf if length $kf;
    push @parts, 'react ' . (defined $d->{frontend}{flag} ? "`$d->{frontend}{flag}`" : '기본 포함') if $d->{frontend};
    my $mods = @{ $d->{modules} } ? ' [' . join(', ', @{ $d->{modules} }) . ']' : '';
    $out .= "- $d->{need}$mods → " . join(' · ', @parts) . " — 손으로: $d->{byHand}\n";
  }
  $out .= "\n";

  if (!$stamped && $cat->{examples} && @{ $cat->{examples} }) {
    $out .= "## 작업 예 (두 레포의 명령 — 순서 · 설정 · 검증 · 배포는 docs/new-project-recipe.md)\n\n";
    for my $x (@{ $cat->{examples} }) {
      $out .= "- $x->{title}\n  - kotlin: `" . example_command($x) . "`\n  - react: `$x->{frontendCommand}`\n";
    }
    $out .= "\n";
  }

  $out .= "## 기능 한눈에 (id: 요약 [키워드] → 켜는 조각)\n\n";
  for my $e (entries($cat)) {
    my @frag = (($stamped ? '' : 'kotlin ') . backend_fragment($e, $stamped));
    push @frag, 'react ' . frontend_fragment($e) if $e->{frontend} && !$stamped;
    $out .= "- $e->{id}: $e->{summary} [" . kw($e, 'ko', 5) . '; ' . kw($e, 'en', 3) . "] → " . join(' · ', @frag) . "\n";
  }
  if ($stamped && $cat->{stampedFrom} && @{ $cat->{stampedFrom}{omitted} }) {
    $out .= "\n## 이 프로젝트에 없는 것 (스켈레톤 레포에는 있다 — 필요하면 거기서 가져온다)\n\n";
    for my $o (@{ $cat->{stampedFrom}{omitted} }) { $out .= "- $o->{id}: $o->{summary}\n"; }
  }
  return $out;
}

# ---------------------------------------------------------------------------------------------------- 실행

sub generated {
  my ($cat) = @_;
  return (
    [ $CATALOG => canonical_text($cat) ],
    [ 'docs/capabilities.md' => render_md($cat) ],
    [ 'llms.txt' => render_llms($cat) ],
  );
}

sub run {
  my (@args) = @_;
  my $check = grep { $_ eq '--check' } @args;
  my $cat = load_catalog($ROOT);
  my @problems;
  for my $g (generated($cat)) {
    my ($rel, $text) = @$g;
    my $now = slurp("$ROOT/$rel");
    if ($check) {
      push @problems, "$rel 가 낡았거나 정본 형식이 아니다" if !defined $now || $now ne $text;
    } else {
      spew("$ROOT/$rel", $text) if !defined $now || $now ne $text;
      print "wrote $rel\n" if !defined $now || $now ne $text;
    }
  }
  if (@problems) {
    print STDERR "capabilities: " . scalar(@problems) . " problem(s)\n";
    print STDERR "  - $_\n" for @problems;
    print STDERR "  → perl scripts/build-capabilities.pl 로 다시 만든다\n";
    return 1;
  }
  printf("capabilities ok — %d entries%s\n", scalar(entries($cat)), $check ? ' (generated docs up to date)' : '');
  return 0;
}

exit(run(@ARGV)) unless caller;
1;
