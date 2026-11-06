local used_key = KEYS[1]
local rejected_key = KEYS[2]
local second_key = KEYS[3]
local period_total_key = KEYS[4]
local near_key = KEYS[5]
local over_key = KEYS[6]
local outcome = ARGV[1]
local period_ttl_seconds = tonumber(ARGV[2])
local second_ttl_seconds = tonumber(ARGV[3])
local limit = tonumber(ARGV[4])
local near_boundary = tonumber(ARGV[5])
local tenant_id = ARGV[6]

local function increment_with_ttl(key, ttl_seconds)
  local value = redis.call('INCR', key)
  if value == 1 then
    redis.call('EXPIRE', key, ttl_seconds)
  end
  return value
end

local function move_tenant(to_key, from_key)
  redis.call('SADD', to_key, tenant_id)
  redis.call('EXPIRE', to_key, period_ttl_seconds, 'NX')
  redis.call('SREM', from_key, tenant_id)
end

local used
if outcome == 'allowed' then
  used = increment_with_ttl(used_key, period_ttl_seconds)
else
  increment_with_ttl(rejected_key, period_ttl_seconds)
  used = tonumber(redis.call('GET', used_key)) or 0
end

increment_with_ttl(second_key, second_ttl_seconds)
increment_with_ttl(period_total_key, period_ttl_seconds)

if used >= limit then
  move_tenant(over_key, near_key)
elseif used >= near_boundary then
  move_tenant(near_key, over_key)
else
  redis.call('SREM', near_key, tenant_id)
  redis.call('SREM', over_key, tenant_id)
end

return used
