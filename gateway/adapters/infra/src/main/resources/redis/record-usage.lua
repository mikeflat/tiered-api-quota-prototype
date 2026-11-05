local used_key = KEYS[1]
local rejected_key = KEYS[2]
local outcome = ARGV[1]
local ttl_seconds = tonumber(ARGV[2])

local function increment_with_ttl(key)
  local value = redis.call('INCR', key)
  if value == 1 then
    redis.call('EXPIRE', key, ttl_seconds)
  end
  return value
end

if outcome == 'allowed' then
  return increment_with_ttl(used_key)
end

increment_with_ttl(rejected_key)
return tonumber(redis.call('GET', used_key)) or 0
