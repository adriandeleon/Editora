package com.editora.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RubyRunLinesTest {

    @Test
    void aScriptRunsFromTheTop() {
        assertEquals(0, RubyRunLines.runLine("job.rb", "require 'json'\n\nputs JSON.generate(a: 1)\n"));
        assertEquals(0, RubyRunLines.runLine("main.rb", "#!/usr/bin/env ruby\ndef main; end\nmain\n"));
        assertEquals(
                0, RubyRunLines.runLine("config.ru", "require_relative 'config/environment'\nrun Rails.application\n"));
        assertEquals(
                0,
                RubyRunLines.runLine(
                        "rails", "#!/usr/bin/env ruby\nAPP_PATH = File.expand_path('../config', __dir__)\n"));
    }

    @Test
    void specAndTestFilesAtTheirDescribeAndClass() {
        assertEquals(2, RubyRunLines.runLine("cart_spec.rb", "require 'spec_helper'\n\nRSpec.describe Cart do\nend\n"));
        assertEquals(
                2,
                RubyRunLines.runLine(
                        "calc_test.rb", "require 'minitest/autorun'\n\nclass CalcTest < Minitest::Test\nend\n"));
    }

    @Test
    void aFileOfDeclarationsHasNoRun() {
        assertEquals(-1, RubyRunLines.runLine("cart.rb", """
                # frozen_string_literal: true
                require "money"

                module Shop
                  class Cart < ApplicationRecord
                    VERSION = "1"
                    def total
                      items.sum(&:price)
                    end
                  end
                end
                """));
        assertEquals(
                -1, RubyRunLines.runLine("x.rb", "=begin\nputs 'commented'\n=end\nclass X\nend\n__END__\nputs 1\n"));
    }
}
